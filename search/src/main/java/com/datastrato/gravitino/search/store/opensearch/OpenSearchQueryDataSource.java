/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS;
import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS_DTO;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
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
  private boolean failed;

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
      searchIndices.put(entityType, openSearch.getIndicesAliasName(entityType, metalake));
    }
  }

  @Override
  public Result nextBatch() {
    Preconditions.checkState(!failed, "This search cursor failed and cannot be resumed");
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

  /** Releases the active scroll, including when authorization or query processing fails. */
  @Override
  public void close() {
    clearScroll();
    searchIndices.clear();
    currentEntityType = null;
  }

  /**
   * Abandons the entity type currently being scrolled, releasing its scroll and continuing with the
   * next type on the following {@link #nextBatch()}.
   */
  @Override
  public void skipCurrentEntityType() {
    clearScroll();
    searchIndices.remove(currentEntityType);
    currentEntityType = null;
  }

  public Result nextBatch(Entity.EntityType entityType) {
    try {
      SearchResponse<? extends SearchEntityPO> response;
      if (currentScrollId == null) {
        SearchRequest searchRequest =
            OpenSearchStorage.createSearchRequestBuilder(keyword, filter, fields, entityType)
                .index(openSearch.getIndicesAliasName(entityType, metalake))
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

      if (response.scrollId() != null) {
        currentScrollId = response.scrollId();
      }

      if (currentScrollId == null && !response.hits().hits().isEmpty()) {
        throw new IllegalStateException("Nonempty search response is missing a scroll ID");
      }

      List<SearchEntityDTO> entities =
          response.hits().hits().stream()
              .map(
                  hit ->
                      SearchEntityCodec.INSTANCE.convert(
                          hit.source(), ENTITY_TYPE_TO_CLASS_DTO.get(entityType)))
              .collect(Collectors.toList());

      if (entities.isEmpty()) {
        clearScroll();
        searchIndices.remove(currentEntityType);
        currentEntityType = null;
        currentScrollId = null;
      }
      return new Result(entities, currentEntityType);
    } catch (Exception e) {
      LOG.error("Batch search failed", e);
      // Release the server-side scroll context now; callers that abort on this failure without
      // closing the data source would otherwise leave it alive until the scroll timeout expires.
      // The cursor is left terminal: resuming it would restart the scroll of this entity type and
      // re-emit entities the caller already consumed.
      failed = true;
      clearScroll();
      throw new RuntimeException("Batch search query failed", e);
    }
  }

  private void clearScroll() {
    if (currentScrollId != null) {
      try {
        openSearch.getClient().clearScroll(request -> request.scrollId(currentScrollId));
      } catch (Exception e) {
        LOG.warn("Failed to clear search scroll", e);
      } finally {
        currentScrollId = null;
      }
    }
  }
}
