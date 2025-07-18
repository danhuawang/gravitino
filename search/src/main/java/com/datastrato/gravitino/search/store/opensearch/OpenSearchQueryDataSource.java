/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS;
import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS_DTO;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.opensearch.client.opensearch.core.ClearScrollRequest;
import org.opensearch.client.opensearch.core.ScrollRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenSearchQueryDataSource implements SearchDataSource {
  private static final Logger LOG = LoggerFactory.getLogger(OpenSearchQueryDataSource.class);

  private final OpenSearchStorage openSearch;
  private final String metalake;
  private final String keyword;
  private final Condition filter;
  private final List<String> fields;
  private final Map<Entity.EntityType, String> searchIndices = new HashMap<>();

  private String currentScrollId;
  private Entity.EntityType currentEntityType;

  public OpenSearchQueryDataSource(
      OpenSearchStorage openSearch,
      Set<Entity.EntityType> entityTypeSet,
      String metalake,
      String keyword,
      Condition filter,
      List<String> fields) {
    this.openSearch = openSearch;
    this.metalake = metalake;
    this.keyword = keyword;
    this.filter = filter;
    this.fields = fields;
    for (Entity.EntityType entityType : entityTypeSet) {
      searchIndices.put(entityType, openSearch.getIndicesName(entityType, metalake));
    }
  }

  @Override
  public Result nextBatch() {
    Result result = Result.empty();
    while (result.isEmpty()) {
      if (searchIndices.isEmpty()) {
        return Result.empty();
      }

      if (currentEntityType == null) {
        // Start a new scroll for the first entity type
        currentEntityType = searchIndices.keySet().iterator().next();
        currentScrollId = null;
      }
      result = nextBatch(currentEntityType);
    }
    return result;
  }

  public Result nextBatch(Entity.EntityType entityType) {
    try {
      SearchResponse<? extends SearchEntityPO> response;
      if (currentScrollId == null) {
        SearchRequest searchRequest =
            OpenSearchStorage.createSearchRequestBuilder(keyword, filter, fields, entityType)
                .index(openSearch.getIndicesName(entityType, metalake))
                .size(1000)
                .scroll(s -> s.time(openSearch.getBackgroundQueryTimeout()))
                .build();

        LOG.debug("Initial scroll query request: {}", searchRequest.toJsonString());
        response =
            openSearch.getClient().search(searchRequest, ENTITY_TYPE_TO_CLASS.get(entityType));
      } else {
        ScrollRequest scrollRequest =
            new ScrollRequest.Builder()
                .scrollId(currentScrollId)
                .scroll(s -> s.time(openSearch.getBackgroundQueryTimeout()))
                .build();
        LOG.debug("Scroll query request: {}", scrollRequest.toJsonString());
        response =
            openSearch.getClient().scroll(scrollRequest, ENTITY_TYPE_TO_CLASS.get(entityType));
      }

      List<SearchEntityDTO> entities =
          response.hits().hits().stream()
              .map(
                  hit ->
                      SearchEntityCodec.INSTANCE.convert(
                          hit.source(), ENTITY_TYPE_TO_CLASS_DTO.get(entityType)))
              .collect(Collectors.toList());
      currentScrollId = response.scrollId();

      if (entities.isEmpty()) {
        ClearScrollRequest clearRequest =
            new ClearScrollRequest.Builder().scrollId(currentScrollId).build();

        openSearch.getClient().clearScroll(clearRequest);
        searchIndices.remove(currentEntityType);
        currentEntityType = null;
        currentScrollId = null;
      }
      return new Result(entities, currentEntityType);
    } catch (Exception e) {
      LOG.error("Batch search failed", e);
      throw new RuntimeException("Batch search query failed", e);
    }
  }
}
