/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import com.datastrato.gravitino.search.dto.SearchCatalogEntityDTO;
import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchTableEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.datastrato.gravitino.search.utils.FilterConditionUtils;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.MultiMatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.search.SourceConfig;
import org.opensearch.client.opensearch.core.search.TrackHits;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.util.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenSearchStorage implements SearchStorage {
  private static final Logger LOG = LoggerFactory.getLogger(OpenSearchStorage.class);

  private static final String CATALOG_ENTITY_SUFFIX = "catalog_entity_index";
  private static final String SCHEMA_ENTITY_INDEX_SUFFIX = "schema_entity_index";
  private static final String FILESET_ENTITY_INDEX_SUFFIX = "fileset_entity_index";
  private static final String MODEL_ENTITY_INDEX_SUFFIX = "model_entity_index";
  private static final String TOPIC_ENTITY_INDEX_SUFFIX = "topic_entity_index";
  private static final String TABLE_ENTITY_INDEX_SUFFIX = "table_entity_index";

  private int maxRetries;
  private long retryBackoffMs;
  private long queryTimeoutMs;

  private OpenSearchClient client;
  private RestClient restClient;
  private ExecutorService queryExecutor;
  private SearchEntityCodec searchEntityCodec;

  private static final Map<EntityType, String> ENTITY_TYPE_TO_INDEX_SUFFIX =
      ImmutableMap.of(
          EntityType.CATALOG, CATALOG_ENTITY_SUFFIX,
          EntityType.SCHEMA, SCHEMA_ENTITY_INDEX_SUFFIX,
          EntityType.FILESET, FILESET_ENTITY_INDEX_SUFFIX,
          EntityType.MODEL, MODEL_ENTITY_INDEX_SUFFIX,
          EntityType.TOPIC, TOPIC_ENTITY_INDEX_SUFFIX,
          EntityType.TABLE, TABLE_ENTITY_INDEX_SUFFIX);

  private static final Map<EntityType, Class<? extends SearchEntityPO>> ENTITY_TYPE_TO_CLASS =
      ImmutableMap.of(
          EntityType.CATALOG, SearchCatalogEntityPO.class,
          EntityType.SCHEMA, SearchEntityPO.class,
          EntityType.FILESET, SearchEntityPO.class,
          EntityType.MODEL, SearchEntityPO.class,
          EntityType.TOPIC, SearchEntityPO.class,
          EntityType.TABLE, SearchTableEntityPO.class);

  private static final Map<EntityType, Class<? extends SearchEntityDTO>> ENTITY_TYPE_TO_CLASS_DTO =
      ImmutableMap.of(
          EntityType.CATALOG, SearchCatalogEntityDTO.class,
          EntityType.SCHEMA, SearchEntityDTO.class,
          EntityType.FILESET, SearchEntityDTO.class,
          EntityType.MODEL, SearchEntityDTO.class,
          EntityType.TOPIC, SearchEntityDTO.class,
          EntityType.TABLE, SearchTableEntityDTO.class);

  private final Map<EntityType, String> entityTypeToIndicesJsonMap = Maps.newHashMap();

  private final Set<String> createdIndices = Sets.newHashSet();

  @Override
  public void initialize(Config config) {
    OpenSearchConfig openSearchConfig = new OpenSearchConfig(config.getAllConfig());

    this.maxRetries = openSearchConfig.getOpenSearchWriteMaxRetry();
    this.retryBackoffMs = openSearchConfig.getOpenSearchRetryBackoffMs();
    this.queryTimeoutMs = openSearchConfig.getOpenSearchQueryTimeoutMs();

    this.queryExecutor = createThreadExecutor(openSearchConfig);
    this.searchEntityCodec = new SearchEntityCodec();

    initOpenSearchClient(openSearchConfig);

    // Load the indices definition from the JSON files
    loadIndicesDefinition();
  }

  private ThreadPoolExecutor createThreadExecutor(OpenSearchConfig openSearchConfig) {
    return new ThreadPoolExecutor(
        openSearchConfig.getOpenSearchMaxQueryThread(),
        openSearchConfig.getOpenSearchMaxQueryThread(),
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingDeque<>(openSearchConfig.getOpenSearchMaxQueryQueueSize()),
        new ThreadFactoryBuilder()
            .setNameFormat("OpenSearch-QueryExecutor-%d")
            .setDaemon(true)
            .build());
  }

  private void loadIndicesDefinition() {
    String entityJson = loadIndexDefFile("indices/opensearch/entity_indices.json");
    String tableJson = loadIndexDefFile("indices/opensearch/table_entity_indices.json");
    String catalogJson = loadIndexDefFile("indices/opensearch/catalog_entity_indices.json");

    entityTypeToIndicesJsonMap.put(EntityType.TABLE, tableJson);
    entityTypeToIndicesJsonMap.put(EntityType.CATALOG, catalogJson);
    entityTypeToIndicesJsonMap.put(EntityType.SCHEMA, entityJson);
    entityTypeToIndicesJsonMap.put(EntityType.FILESET, entityJson);
    entityTypeToIndicesJsonMap.put(EntityType.MODEL, entityJson);
    entityTypeToIndicesJsonMap.put(EntityType.TOPIC, entityJson);
  }

  private String loadIndexDefFile(String filePath) {
    try (InputStream stream =
        OpenSearchStorage.class.getClassLoader().getResourceAsStream(filePath)) {
      if (stream == null) {
        String msg = String.format("Resource not found: %s", filePath);
        LOG.error(msg);
        throw new RuntimeException(msg);
      }
      return IOUtils.toString(stream, StandardCharsets.UTF_8);
    } catch (Exception e) {
      String msg = String.format("Failed to load JSON file: %s", filePath);
      LOG.error(msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  private synchronized String createIndicesIfNotExists(EntityType entityType, String metalakeName) {
    String indicesName = getIndicesName(entityType, metalakeName);
    if (createdIndices.contains(indicesName)) {
      return indicesName;
    }

    createEntityIndices(entityType, indicesName);
    createdIndices.add(indicesName);
    return indicesName;
  }

  private String getIndicesName(EntityType entityType, String metalakeName) {
    String indicesSuffix = ENTITY_TYPE_TO_INDEX_SUFFIX.get(entityType);
    return metalakeName + "_" + indicesSuffix;
  }

  private void saveToStorage(List<SearchEntityPO> entities, boolean flush) {
    // Group by SearchEntityPO by metalake
    Map<String, List<SearchEntityPO>> metalakeToEntitiesMap =
        entities.stream()
            .collect(Collectors.groupingBy(SearchEntityPO::getMetalake, Collectors.toList()));

    for (Map.Entry<String, List<SearchEntityPO>> entry : metalakeToEntitiesMap.entrySet()) {
      String metalakeName = entry.getKey();
      Map<EntityType, List<SearchEntityPO>> entityMap =
          entry.getValue().stream().collect(Collectors.groupingBy(SearchEntityPO::getEntityType));

      for (Map.Entry<EntityType, List<SearchEntityPO>> entityTypeListEntry : entityMap.entrySet()) {
        EntityType entityType = entityTypeListEntry.getKey();
        List<SearchEntityPO> entityList = entityTypeListEntry.getValue();

        saveToIndex(metalakeName, entityType, entityList, flush);
      }
    }
  }

  private void saveToIndex(
      String metalakeName, EntityType type, List<SearchEntityPO> entityPOs, boolean flush) {
    String indicesName = createIndicesIfNotExists(type, metalakeName);
    try {
      BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();
      for (SearchEntityPO entity : entityPOs) {
        try {
          bulkRequestBuilder
              .operations(
                  op ->
                      op.index(
                          idx ->
                              idx.index(indicesName)
                                  .id(String.valueOf(entity.getEntityId()))
                                  .document(entity)))
              .refresh(flush ? Refresh.True : Refresh.False);
        } catch (Exception e) {
          LOG.error("Failed to write to OpenSearch", e);
          throw new RuntimeException(e);
        }
      }
      BulkRequest bulkRequest = bulkRequestBuilder.build();

      sendRequestWithRetry(bulkRequest);
    } catch (Exception e) {
      LOG.error("Failed to write to OpenSearch", e);
      throw new RuntimeException(e);
    }
  }

  private BulkResponse sendRequestWithRetry(BulkRequest request) {
    int retry = maxRetries;
    boolean success = false;
    BulkResponse response = null;

    while (retry-- > 0) {
      try {
        response = client.bulk(request);
        if (!response.errors()) {
          return response;
        }
        Thread.sleep(retryBackoffMs * (maxRetries - retry));
      } catch (Exception e) {
        LOG.warn("Failed to request to OpenSearch", e);
      }
    }

    if (!success) {
      if (response != null) {
        logDetailedErrors(response);
      }

      // Throw an exception if any errors occurred after retries. Why not just log it?
      // Because we want to fail the whole thread if we can't write to OpenSearch.
      throw new RuntimeException("OpenSearch server handle request error");
    }
    return response;
  }

  private void logDetailedErrors(BulkResponse response) {
    StringBuilder errorDetails = new StringBuilder();
    for (BulkResponseItem item : response.items()) {
      if (item.error() != null) {
        ErrorCause errorCause = item.error();
        errorDetails
            .append("\n  - Document ID: ")
            .append(item.id())
            .append(", Index: ")
            .append(item.index())
            .append(", Error Type: ")
            .append(errorCause.type())
            .append(", Reason: ")
            .append(errorCause.reason())
            .append(", Stack Trace: ")
            .append(errorCause.stackTrace())
            .append(", Caused by: ")
            .append(errorCause.causedBy() != null ? errorCause.causedBy().reason() : "null");
      }
    }
    LOG.error("Bulk operation errors:{}", errorDetails);
  }

  private void initOpenSearchClient(OpenSearchConfig openSearchConfig) {
    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
    credentialsProvider.setCredentials(
        AuthScope.ANY,
        new UsernamePasswordCredentials(
            openSearchConfig.getOpenSearchUsername(), openSearchConfig.getOpenSearchPassword()));

    try {
      SSLContext sslContext =
          SSLContextBuilder.create().loadTrustMaterial((chain, authType) -> true).build();
      this.restClient =
          RestClient.builder(HttpHost.create(openSearchConfig.getOpenSearchUrl()))
              .setHttpClientConfigCallback(
                  httpClientBuilder ->
                      httpClientBuilder
                          .setDefaultCredentialsProvider(credentialsProvider)
                          .setSSLContext(sslContext))
              .build();

      RestClientTransport transport =
          new RestClientTransport(
              restClient, new JacksonJsonpMapper(searchEntityCodec.objectMapper()));
      this.client = new OpenSearchClient(transport);
    } catch (Exception e) {
      LOG.error("Failed to init OpenSearch client", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void write(List<SearchEntityPO> entities) {
    saveToStorage(entities, false);
  }

  public void write(List<SearchEntityPO> entities, boolean flush) {
    saveToStorage(entities, flush);
  }

  @Override
  public void close() {
    if (client != null) {
      client.shutdown();
    }
  }

  private void createEntityIndices(EntityType entityType, String indicesName) {
    String entityJson = entityTypeToIndicesJsonMap.get(entityType);
    if (entityJson == null) {
      throw new IllegalArgumentException("No JSON definition found for entity type: " + entityType);
    }

    try {
      BooleanResponse exists = client.indices().exists(e -> e.index(indicesName));
      if (exists.value()) {
        LOG.info("Index {} already exists, skipping creation.", indicesName);
        return;
      }
    } catch (Exception e) {
      LOG.error("Failed to check if index {} exists: {}", indicesName, e.getMessage());
      throw new RuntimeException("Failed to check if index " + indicesName + " exists", e);
    }

    Request request = new Request("PUT", "/" + indicesName);
    request.setJsonEntity(entityJson);

    try {
      sendHttpRequestWithRetry(request);
    } catch (Exception e) {
      LOG.error("Failed to create index {}: {}", indicesName, e.getMessage());
    }
  }

  private void sendHttpRequestWithRetry(Request request) {
    int retryTimes = maxRetries;
    while (retryTimes-- > 0) {
      try {
        Response response = restClient.performRequest(request);
        if (response.getStatusLine().getStatusCode()
            == javax.ws.rs.core.Response.Status.OK.getStatusCode()) {
          return;
        }

        LOG.warn(
            "Failed to send request to server: {}", response.getStatusLine().getReasonPhrase());
        Thread.sleep(retryBackoffMs * (maxRetries - retryTimes));
      } catch (Exception e) {
        LOG.warn("Failed to send request to server", e);
      }
    }

    throw new RuntimeException("Failed to send request to server after retries");
  }

  @Override
  public List<SearchEntitiesDTO> search(
      String metalake,
      String keyword,
      Condition filter,
      List<String> fields,
      int pageSize,
      int pageNum) {
    List<SearchEntitiesDTO> result = new ArrayList<>();

    Function<SourceConfig.Builder, ObjectBuilder<SourceConfig>> sourceConfig;
    if (fields != null && !fields.isEmpty()) {
      sourceConfig = src -> src.filter(f -> f.includes(fields));
    } else {
      sourceConfig = src -> src.fetch(true);
    }

    List<Future<Pair<EntityType, SearchResponse<? extends SearchEntityPO>>>> futures =
        Lists.newArrayList();
    for (Map.Entry<EntityType, String> entry : ENTITY_TYPE_TO_INDEX_SUFFIX.entrySet()) {
      EntityType entityType = entry.getKey();
      String indexName = getIndicesName(entityType, metalake);
      SearchRequest searchRequest =
          new SearchRequest.Builder()
              .index(indexName)
              .query(query -> query.bool(buildBoolQuery(keyword, filter, entityType)))
              .source(sourceConfig)
              .from(pageNum * pageSize)
              .size(pageSize)
              // Ignore unavailable indices to avoid errors if the index does not exist
              .trackTotalHits(TrackHits.of(t -> t.enabled(true)))
              .ignoreUnavailable(true)
              .build();

      futures.add(
          queryExecutor.submit(
              () -> {
                try {
                  LOG.info("Query: {}", searchRequest.toJsonString());
                  return Pair.of(
                      entityType,
                      client.search(searchRequest, ENTITY_TYPE_TO_CLASS.get(entityType)));
                } catch (Exception e) {
                  LOG.error("Failed to query OpenSearch", e);
                  throw new RuntimeException(e);
                }
              }));
    }

    for (Future<Pair<EntityType, SearchResponse<? extends SearchEntityPO>>> future : futures) {
      try {
        Pair<EntityType, SearchResponse<? extends SearchEntityPO>> responsePair =
            future.get(queryTimeoutMs, TimeUnit.MILLISECONDS);
        SearchResponse<? extends SearchEntityPO> response = responsePair.getRight();
        EntityType entityType = responsePair.getLeft();
        if (response.hits().total().value() == 0) {
          continue;
        }
        List<? extends SearchEntityDTO> entities =
            response.hits().hits().stream()
                .map(
                    hit ->
                        searchEntityCodec.convert(
                            hit.source(), ENTITY_TYPE_TO_CLASS_DTO.get(entityType)))
                .collect(Collectors.toList());

        result.add(
            SearchEntitiesDTO.builder()
                .withEntities(entities)
                .withTotalSize((int) response.hits().total().value())
                .withType(entityType)
                .build());
      } catch (TimeoutException e) {
        LOG.error("Query timeout in open search", e);
        throw new GravitinoRuntimeException("Query timeout in open search", e);
      } catch (Exception e) {
        LOG.error("Failed to query OpenSearch", e);
        throw new RuntimeException(e);
      }
    }

    return result;
  }

  @Override
  public void delete(String metalake, List<Long> entityIds, EntityType entityType) {
    try {
      BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();
      String indexName = getIndicesName(entityType, metalake);
      for (Long entityId : entityIds) {
        bulkRequestBuilder.operations(
            op -> op.delete(del -> del.index(indexName).id(String.valueOf(entityId))));
      }
      client.bulk(bulkRequestBuilder.build());
    } catch (Exception e) {
      LOG.error(
          "Delete request failed for entity type {} in metalake {}: {}",
          entityType,
          metalake,
          e.getMessage());
    }
  }

  private static BoolQuery buildBoolQuery(String keyword, Condition filter, EntityType entityType) {
    if (StringUtils.isBlank(keyword)) {
      return new BoolQuery.Builder().filter(buildConditionPart(filter)).build();
    }

    return new BoolQuery.Builder()
        .must(buildKeywordPart(keyword, entityType))
        .filter(buildConditionPart(filter))
        .build();
  }

  private static Query buildKeywordPart(String keyword, EntityType entityType) {
    List<String> queryWords =
        Splitter.on(" ").omitEmptyStrings().trimResults().splitToList(keyword);
    List<Query> allQueries = new ArrayList<>();

    for (String word : queryWords) {
      allQueries.addAll(buildMultiMatchQueries(word));
      allQueries.add(buildTagNestedQuery(word));

      if (entityType == EntityType.TABLE) {
        allQueries.add(buildColumnNestedQuery(word));
      }

      // Query word match content in properties.
      allQueries.add(buildPropertiesNestedQuery(word));
    }

    return Query.of(q -> q.bool(b -> b.should(allQueries).minimumShouldMatch("1")));
  }

  private static List<Query> buildMultiMatchQueries(String word) {
    List<Query> queries = new ArrayList<>();

    MultiMatchQuery bestFieldsQuery =
        MultiMatchQuery.of(
            m ->
                m.query(word)
                    .fields("entity_name^4.0", "entity_comment^3.0")
                    .type(TextQueryType.BestFields)
                    .operator(Operator.Or));
    queries.add(Query.of(q -> q.multiMatch(bestFieldsQuery)));

    MultiMatchQuery phraseQuery =
        MultiMatchQuery.of(
            m ->
                m.query(word)
                    .fields("entity_name.ngram^4.0", "entity_comment.ngram^3.0")
                    .analyzer("standard")
                    .type(TextQueryType.Phrase)
                    .slop(0));
    queries.add(Query.of(q -> q.multiMatch(phraseQuery)));

    return queries;
  }

  private static Query buildTagNestedQuery(String word) {
    List<Query> shouldQueries =
        ImmutableList.of(
            Query.of(
                q ->
                    q.match(
                        m ->
                            m.field("tags.tag_name.keyword")
                                .query(FieldValue.of(word))
                                .boost(2.0f))),
            Query.of(
                q ->
                    q.match(
                        mp ->
                            mp.field("tags.tag_name.ngram")
                                .analyzer("standard")
                                .query(FieldValue.of(word))
                                .boost(2.0f))));

    return Query.of(
        q -> q.nested(n -> n.path("tags").query(qb -> qb.bool(b -> b.should(shouldQueries)))));
  }

  private static Query buildPropertiesNestedQuery(String word) {
    List<Query> shouldQueries =
        ImmutableList.of(
            Query.of(
                q ->
                    q.match(
                        m ->
                            m.field("entity_properties.key.keyword")
                                .query(FieldValue.of(word))
                                .boost(1.0f))),
            Query.of(
                q ->
                    q.match(
                        mp ->
                            mp.field("entity_properties.key.ngram")
                                .query(FieldValue.of(word))
                                .analyzer("standard")
                                .boost(1.0f))),
            Query.of(
                q ->
                    q.match(
                        m ->
                            m.field("entity_properties.value.keyword")
                                .query(FieldValue.of(word))
                                .boost(1.0f))),
            Query.of(
                q ->
                    q.match(
                        mp ->
                            mp.field("entity_properties.value.ngram")
                                .query(FieldValue.of(word))
                                .analyzer("standard")
                                .boost(1.0f))));

    return Query.of(
        q ->
            q.nested(
                n ->
                    n.path("entity_properties")
                        .query(qb -> qb.bool(b -> b.should(shouldQueries)))));
  }

  private static Query buildColumnNestedQuery(String word) {
    List<Query> shouldQueries =
        ImmutableList.of(
            Query.of(
                q ->
                    q.match(
                        m ->
                            m.field("columns.column_name.keyword")
                                .query(FieldValue.of(word))
                                .boost(2.0f))),
            Query.of(
                q ->
                    q.match(
                        mp ->
                            mp.field("columns.column_name.ngram")
                                .query(FieldValue.of(word))
                                .analyzer("standard")
                                .boost(2.5f))));

    return Query.of(
        q -> q.nested(n -> n.path("columns").query(qb -> qb.bool(b -> b.should(shouldQueries)))));
  }

  private static List<Query> buildConditionPart(Condition filter) {
    List<Query> result = Lists.newArrayList();
    if (filter == null) {
      return result;
    }

    // Convert the filter condition to OpenSearch query. ImmutableMap.of("tag_name", "tags") means
    // tag_name is a nested field, and tags is the root field. ImmutableMap.of("tag_name",
    // "tags.tag_name.keyword") means tag_name is a keyword field and tags.tag_name is the field
    // name.
    Query query =
        FilterConditionUtils.convert(
            filter,
            ImmutableMap.of("tag_name", "tags"),
            ImmutableMap.of(
                "tag_name", "tags.tag_name.keyword", "catalog_name", "catalog_name.keyword"));
    result.add(query);
    return result;
  }
}
