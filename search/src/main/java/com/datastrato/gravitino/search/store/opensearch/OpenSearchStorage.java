/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS;
import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS_DTO;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.datastrato.gravitino.search.store.WriteContext;
import com.datastrato.gravitino.search.utils.FilterConditionUtils;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
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
import lombok.AllArgsConstructor;
import lombok.Getter;
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
import org.opensearch.client.opensearch._types.OpenSearchException;
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
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.GetAliasResponse;
import org.opensearch.client.opensearch.indices.GetIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.GetIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.UpdateAliasesRequest;
import org.opensearch.client.opensearch.indices.UpdateAliasesResponse;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplateItem;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.util.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenSearchStorage implements SearchStorage {
  private static final Logger LOG = LoggerFactory.getLogger(OpenSearchStorage.class);

  private static final String INDEX_NAME_SEPARATOR = "_";

  private static final String CATALOG_ENTITY_SUFFIX = "catalog_entity_index";
  private static final String SCHEMA_ENTITY_INDEX_SUFFIX = "schema_entity_index";
  private static final String FILESET_ENTITY_INDEX_SUFFIX = "fileset_entity_index";
  private static final String MODEL_ENTITY_INDEX_SUFFIX = "model_entity_index";
  private static final String TOPIC_ENTITY_INDEX_SUFFIX = "topic_entity_index";
  private static final String TABLE_ENTITY_INDEX_SUFFIX = "table_entity_index";
  private static final String VIEW_ENTITY_INDEX_SUFFIX = "view_entity_index";
  private static final String USER_ENTITY_INDEX_SUFFIX = "user_entity_index";
  private static final String GROUP_ENTITY_INDEX_SUFFIX = "group_entity_index";
  private static final String FUNCTION_ENTITY_INDEX_SUFFIX = "function_entity_index";

  private int maxRetries;
  private long retryBackoffMs;
  private long queryTimeoutMs;
  private String backgroundQueryTimeout;

  private OpenSearchClient client;
  private RestClient restClient;
  private ExecutorService queryExecutor;
  private SearchEntityCodec searchEntityCodec;

  protected static final Map<EntityType, String> ENTITY_TYPE_TO_INDEX_SUFFIX =
      ImmutableMap.of(
          EntityType.CATALOG, CATALOG_ENTITY_SUFFIX,
          EntityType.SCHEMA, SCHEMA_ENTITY_INDEX_SUFFIX,
          EntityType.FILESET, FILESET_ENTITY_INDEX_SUFFIX,
          EntityType.MODEL, MODEL_ENTITY_INDEX_SUFFIX,
          EntityType.TOPIC, TOPIC_ENTITY_INDEX_SUFFIX,
          EntityType.TABLE, TABLE_ENTITY_INDEX_SUFFIX,
          EntityType.VIEW, VIEW_ENTITY_INDEX_SUFFIX,
          EntityType.USER, USER_ENTITY_INDEX_SUFFIX,
          EntityType.GROUP, GROUP_ENTITY_INDEX_SUFFIX,
          EntityType.FUNCTION, FUNCTION_ENTITY_INDEX_SUFFIX);

  private final Set<String> createdIndicesAlias = Sets.newHashSet();

  private static final Map<Long, OpenSearchStorageTransaction> TRANSACTION_MAP =
      Maps.newConcurrentMap();

  @Override
  public void initialize(Config config) {
    OpenSearchConfig openSearchConfig = new OpenSearchConfig(config.getAllConfig());

    this.maxRetries = openSearchConfig.getOpenSearchWriteMaxRetry();
    this.retryBackoffMs = openSearchConfig.getOpenSearchRetryBackoffMs();
    this.queryTimeoutMs = openSearchConfig.getOpenSearchQueryTimeoutMs();
    this.backgroundQueryTimeout = openSearchConfig.getOpenSearchBackgroundQueryTimeout();

    this.queryExecutor = createThreadExecutor(openSearchConfig);
    this.searchEntityCodec = SearchEntityCodec.INSTANCE;

    initOpenSearchClient(openSearchConfig);
    checkIndexTemplate();
  }

  private void checkIndexTemplate() {
    try {
      // The index template should be created before the Gravitino server starts.
      // The name of the index template like "fileset_entity_index_template_v1",
      GetIndexTemplateRequest request = new GetIndexTemplateRequest.Builder().build();
      GetIndexTemplateResponse response = client.indices().getIndexTemplate(request);
      List<IndexTemplateItem> templates = response.indexTemplates();
      List<String> templateNames =
          templates.stream().map(IndexTemplateItem::name).collect(Collectors.toList());
      for (String v : ENTITY_TYPE_TO_INDEX_SUFFIX.values()) {
        boolean found = templateNames.stream().anyMatch(name -> name.startsWith(v));
        if (!found) {
          LOG.error("Index template for {} not found", v);
          throw new Exception("Index template for " + v + " not found");
        }
      }

    } catch (Exception e) {
      LOG.error("Failed to check index template: {}", e.getMessage());
      throw new RuntimeException("Failed to check index template", e);
    }
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

  private synchronized String createIndicesIfNotExists(EntityType entityType, String metalakeName)
      throws Exception {
    // getIndicesName will return an alias name, we will treat the alias name as the index name here
    String indicesAliasName = getIndicesAliasName(entityType, metalakeName);
    if (createdIndicesAlias.contains(indicesAliasName)) {
      return indicesAliasName;
    }

    // check if index alias already exists
    try {
      GetAliasResponse getAliasResponse = client.indices().getAlias(r -> r.name(indicesAliasName));
      Set<String> oldIndices = getAliasResponse.result().keySet();
      String oldIndexName = oldIndices.stream().findFirst().orElse(null);
      if (oldIndexName != null) {
        createdIndicesAlias.add(indicesAliasName);
        return indicesAliasName;
      }
    } catch (OpenSearchException e) {
      if (e.status() != HTTP_NOT_FOUND) {
        throw e;
      }
    }

    // if index alias does not exists, create a new index with version 0 and update alias
    String indicesName = createEntityIndices(indicesAliasName, 0);
    updateIndexAlias(indicesName, indicesAliasName);
    createdIndicesAlias.add(indicesAliasName);
    return indicesName;
  }

  protected String getIndicesAliasName(EntityType entityType, String metalakeName) {
    // Create the index alias name based on the entity type and metalake name
    String indicesSuffix = ENTITY_TYPE_TO_INDEX_SUFFIX.get(entityType);
    return metalakeName + INDEX_NAME_SEPARATOR + indicesSuffix;
  }

  public OpenSearchClient getClient() {
    return client;
  }

  public String getBackgroundQueryTimeout() {
    return backgroundQueryTimeout;
  }

  private void saveToStorage(
      List<SearchEntityPO> entities, WriteContext writeContext, boolean flush) {
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

        saveToIndex(metalakeName, entityType, entityList, flush, writeContext);
      }
    }
  }

  private void saveToIndex(
      String metalakeName,
      EntityType type,
      List<SearchEntityPO> entityPOs,
      boolean flush,
      WriteContext context) {
    try {
      String indicesName = createIndicesIfNotExists(type, metalakeName);

      // In case of transaction, append the transaction ID to the index name
      if (context.getTransactionId() != null) {
        indicesName = indicesName + "_" + context.getTransactionId();
      }
      final String realIndicesName = indicesName;

      BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();
      for (SearchEntityPO entity : entityPOs) {
        try {
          bulkRequestBuilder
              .operations(
                  op ->
                      op.index(
                          idx ->
                              idx.index(realIndicesName)
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
  public void write(List<SearchEntityPO> entities, WriteContext writeContext) {
    saveToStorage(entities, writeContext, true);
  }

  public void write(List<SearchEntityPO> entities, boolean flush) {
    saveToStorage(entities, WriteContext.DEFAULT, flush);
  }

  @Override
  public void close() {
    if (client != null) {
      client.shutdown();
    }
  }

  private String createEntityIndices(String indexAliasName, long version) {
    // Create a new index based on the template.
    // the index template name is the part os alias name, like "*fileset_entity_index*".
    // the index alias name will be "metalake_fileset_entity_index",
    // the index name will be "metalake_fileset_entity_index_1234567890"
    String realIndicesName = indexAliasName + INDEX_NAME_SEPARATOR + version;
    try {
      // Check if the index already exists
      ExistsRequest existsRequest = new ExistsRequest.Builder().index(realIndicesName).build();
      boolean exists = client.indices().exists(existsRequest).value();
      if (exists) {
        return realIndicesName;
      }
      Request request = new Request("PUT", "/" + realIndicesName);
      sendHttpRequestWithRetry(request);
      return realIndicesName;
    } catch (Exception e) {
      LOG.error(
          "Failed to create index {} by template {}: {}",
          realIndicesName,
          indexAliasName,
          e.getMessage());
      throw new RuntimeException(e);
    }
  }

  private String updateIndexAlias(String indicesName, String aliasName) throws Exception {
    try {
      Preconditions.checkArgument(
          !indicesName.equals(aliasName),
          "Index and alias name %s should not be same",
          indicesName);
      String oldIndexName = null;
      try {
        GetAliasResponse getAliasResponse = client.indices().getAlias(r -> r.name(aliasName));
        Set<String> oldIndices = getAliasResponse.result().keySet();
        oldIndexName = oldIndices.stream().findFirst().orElse(null);
      } catch (OpenSearchException e) {
        if (e.status() != HTTP_NOT_FOUND) {
          throw e;
        }
      }

      UpdateAliasesRequest.Builder builder = new UpdateAliasesRequest.Builder();
      if (oldIndexName != null) {
        String finalOldIndexName = oldIndexName;
        builder.actions(a -> a.remove(r -> r.index(finalOldIndexName).alias(aliasName)));
      }

      builder.actions(a -> a.add(a2 -> a2.index(indicesName).alias(aliasName).isWriteIndex(true)));

      UpdateAliasesResponse updateAliasesResponse = client.indices().updateAliases(builder.build());

      if (updateAliasesResponse.acknowledged()) {
        LOG.info("Successfully update index alias {} with index {}", aliasName, indicesName);
      } else {
        LOG.error("Failed to update alias {} for index {}", aliasName, indicesName);
        throw new RuntimeException(
            "Failed to update alias " + aliasName + " for index " + indicesName);
      }
      return oldIndexName;
    } catch (Exception e) {
      LOG.error(
          "Failed to update index alias {} with index {} : {}",
          aliasName,
          indicesName,
          e.getMessage());
      throw e;
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

    List<Future<Pair<EntityType, SearchResponse<? extends SearchEntityPO>>>> futures =
        Lists.newArrayList();
    for (Map.Entry<EntityType, String> entry : ENTITY_TYPE_TO_INDEX_SUFFIX.entrySet()) {
      EntityType entityType = entry.getKey();
      String indexName = getIndicesAliasName(entityType, metalake);

      SearchRequest searchRequest =
          createSearchRequestBuilder(keyword, filter, fields, entityType)
              .index(indexName)
              .from(pageNum * pageSize)
              .size(pageSize)
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
        throw new GravitinoRuntimeException(e, "Query timeout in open search");
      } catch (Exception e) {
        LOG.error("Failed to query OpenSearch", e);
        throw new RuntimeException(e);
      }
    }

    return result;
  }

  @Override
  public SearchDataSource search(
      String metalake, String keyword, Condition filter, List<String> fields) {
    return new OpenSearchQueryDataSource(
        this, ENTITY_TYPE_TO_INDEX_SUFFIX.keySet(), metalake, keyword, filter, fields);
  }

  protected static SearchRequest.Builder createSearchRequestBuilder(
      String keyword, Condition filter, List<String> fields, EntityType entityType) {
    Function<SourceConfig.Builder, ObjectBuilder<SourceConfig>> sourceConfig;
    if (fields != null && !fields.isEmpty()) {
      sourceConfig = src -> src.filter(f -> f.includes(fields));
    } else {
      sourceConfig = src -> src.fetch(true);
    }

    return new SearchRequest.Builder()
        .query(query -> query.bool(buildBoolQuery(keyword, filter, entityType)))
        .source(sourceConfig)
        // Ignore unavailable indices to avoid errors if the index does not exist
        .trackTotalHits(TrackHits.of(t -> t.enabled(true)))
        .ignoreUnavailable(true);
  }

  @Override
  public void delete(String metalake, List<Long> entityIds, EntityType entityType) {
    try {
      BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();
      String indexName = getIndicesAliasName(entityType, metalake);
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
        allQueries.add(
            buildStringNestedQuery("columns", "column_name", word)); // Match column names in tables
      }

      if (entityType == EntityType.MODEL) {
        allQueries.add(buildStringNestedQuery("model_versions", "aliases", word));
      }

      if (entityType == EntityType.VIEW) {
        // Match column names in views, same as tables.
        allQueries.add(buildStringNestedQuery("columns", "column_name", word));
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

    // Not every entity index maps "tags": queries run once per entity index, and an index that
    // does not declare the path must contribute no matches instead of failing the whole search.
    return Query.of(
        q ->
            q.nested(
                n ->
                    n.path("tags")
                        .ignoreUnmapped(true)
                        .query(qb -> qb.bool(b -> b.should(shouldQueries)))));
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
                        .ignoreUnmapped(true)
                        .query(qb -> qb.bool(b -> b.should(shouldQueries)))));
  }

  private static Query buildStringNestedQuery(
      String columnName, String columnNestedField, String word) {
    List<Query> shouldQueries =
        ImmutableList.of(
            Query.of(
                q ->
                    q.match(
                        m ->
                            m.field(columnName + "." + columnNestedField + ".keyword")
                                .query(FieldValue.of(word))
                                .boost(2.0f))),
            Query.of(
                q ->
                    q.match(
                        mp ->
                            mp.field(columnName + "." + columnNestedField + ".ngram")
                                .query(FieldValue.of(word))
                                .analyzer("standard")
                                .boost(2.5f))));

    return Query.of(
        q ->
            q.nested(
                n ->
                    n.path(columnName)
                        .ignoreUnmapped(true)
                        .query(qb -> qb.bool(b -> b.should(shouldQueries)))));
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

  boolean checkMetalakeTransactionExists(String metalake) {
    return TRANSACTION_MAP.values().stream()
        .anyMatch(transaction -> transaction.getMetalake().equals(metalake));
  }

  @Override
  public long beginTransaction(String metalake) {
    if (checkMetalakeTransactionExists(metalake)) {
      throw new IllegalStateException("Transaction already exists for metalake: " + metalake);
    }
    long now = System.currentTimeMillis();
    // Create temporary indices for the transaction
    Set<String> indicesSet = Sets.newHashSet();
    for (EntityType entityType : ENTITY_TYPE_TO_INDEX_SUFFIX.keySet()) {
      // Should use transaction id not tmp indices name
      String indicesName = createEntityIndices(getIndicesAliasName(entityType, metalake), now);
      indicesSet.add(indicesName);
    }
    TRANSACTION_MAP.put(now, new OpenSearchStorageTransaction(now, metalake, indicesSet));
    return now;
  }

  @Override
  public void commit(long transactionId) {
    // Switch alias. add alias for temp indices and then remove the old alias.
    OpenSearchStorageTransaction transaction = TRANSACTION_MAP.get(transactionId);
    Set<String> newIndexes = transaction.getIndexNames();
    Set<String> oldIndexNames = Sets.newHashSet();

    try {
      for (String indices : newIndexes) {
        String aliasName = indices.substring(0, indices.lastIndexOf(INDEX_NAME_SEPARATOR));
        String oldIndicesName = updateIndexAlias(indices, aliasName);
        oldIndexNames.add(oldIndicesName);
      }

      // Delete old indices, this can be done in a separate thread or asynchronously.
      for (String oldIndexName : oldIndexNames) {
        try {
          client.indices().delete(d -> d.index(oldIndexName));
        } catch (Exception e) {
          LOG.error("Failed to delete old index {}: {}", oldIndexName, e.getMessage());
        }
      }

      // remove data from transaction map
      TRANSACTION_MAP.remove(transactionId);
    } catch (Exception e) {
      LOG.error("Failed to commit transaction: {}", e.getMessage());
      throw new RuntimeException("Failed to commit transaction", e);
    }
  }

  @AllArgsConstructor
  @Getter
  public static class IndexStatus {
    private boolean exists;
    private boolean isAlias;
    private String targetIndex; // If isAlias is true, this is the target index of the alias.
  }

  @Override
  public void rollback(long transactionId) {
    OpenSearchStorageTransaction transaction = TRANSACTION_MAP.remove(transactionId);
    if (transaction == null) {
      LOG.warn("No transaction found for ID: {}", transactionId);
      return;
    }

    Set<String> indicesToDelete = transaction.getIndexNames();
    if (indicesToDelete == null || indicesToDelete.isEmpty()) {
      LOG.warn("No indices found for transaction ID: {}", transactionId);
      return;
    }

    // Delete the temporary indices created for the transaction
    for (String indexName : indicesToDelete) {
      try {
        client.indices().delete(d -> d.index(indexName));
      } catch (Exception e) {
        LOG.error("Failed to delete index {}: {}", indexName, e.getMessage());
      }
    }
  }
}
