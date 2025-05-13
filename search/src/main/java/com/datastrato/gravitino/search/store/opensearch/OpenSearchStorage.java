/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.apache.commons.io.IOUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.json.JsonUtils;
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
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.transport.rest_client.RestClientTransport;
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

  private OpenSearchClient client;
  private RestClient restClient;

  private static final Map<EntityType, String> ENTITY_TYPE_TO_INDEX_SUFFIX =
      ImmutableMap.of(
          EntityType.CATALOG, CATALOG_ENTITY_SUFFIX,
          EntityType.SCHEMA, SCHEMA_ENTITY_INDEX_SUFFIX,
          EntityType.FILESET, FILESET_ENTITY_INDEX_SUFFIX,
          EntityType.MODEL, MODEL_ENTITY_INDEX_SUFFIX,
          EntityType.TOPIC, TOPIC_ENTITY_INDEX_SUFFIX,
          EntityType.TABLE, TABLE_ENTITY_INDEX_SUFFIX);

  private ObjectMapper objectMapper;
  private final Map<EntityType, String> entityTypeToIndicesJsonMap = Maps.newHashMap();

  private final Set<String> createdIndices = Sets.newHashSet();

  @Override
  public void initialize(Config config) {
    OpenSearchConfig openSearchConfig = new OpenSearchConfig(config.getAllConfig());

    this.maxRetries = openSearchConfig.getOpenSearchWriteMaxRetry();
    this.retryBackoffMs = openSearchConfig.getOpenSearchRetryBackoffMs();

    initOpenSearchClient(openSearchConfig);
    this.objectMapper = JsonUtils.objectMapper();

    // Load the indices definition from the JSON files
    loadIndicesDefinition();
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

  private void saveToStorage(List<SearchEntityPO> entities) {
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

        saveToIndex(metalakeName, entityType, entityList);
      }
    }
  }

  private void saveToIndex(String metalakeName, EntityType type, List<SearchEntityPO> entityPOs) {
    String indicesName = createIndicesIfNotExists(type, metalakeName);
    try {
      BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();
      for (SearchEntityPO entity : entityPOs) {
        try {
          Map<String, Object> doc = objectMapper.convertValue(entity, Map.class);
          bulkRequestBuilder.operations(
              op ->
                  op.index(
                      idx ->
                          idx.index(indicesName)
                              .id(String.valueOf(entity.getEntityId()))
                              .document(doc)));
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
      throw new RuntimeException("Failed to write to OpenSearch after retries");
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
          new RestClientTransport(restClient, new JacksonJsonpMapper(JsonUtils.objectMapper()));
      this.client = new OpenSearchClient(transport);
    } catch (Exception e) {
      LOG.error("Failed to init OpenSearch client", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void write(List<SearchEntityPO> entities) {
    saveToStorage(entities);
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

  private Response sendHttpRequestWithRetry(Request request) {
    int retryTimes = maxRetries;
    Response response = null;
    while (retryTimes-- > 0) {
      try {
        response = restClient.performRequest(request);
        if (response.getStatusLine().getStatusCode()
            == javax.ws.rs.core.Response.Status.OK.getStatusCode()) {
          return response;
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
      String keyword, Condition filter, int pageSize, int pageNum) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
