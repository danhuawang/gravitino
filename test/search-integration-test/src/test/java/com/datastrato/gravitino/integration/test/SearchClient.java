/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS_DTO;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.rest.SyncMetadataResponse;
import com.datastrato.gravitino.search.rest.TaskStatusResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.client.ObjectMapperProvider;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;

public class SearchClient {

  private final CloseableHttpClient client;
  private final ObjectMapper mapper;
  private final String url;

  public SearchClient(String baseUrl) {
    this.url = baseUrl;
    this.client = HttpClients.createDefault();
    this.mapper = ObjectMapperProvider.objectMapper();
  }

  public List<SearchEntitiesDTO> search(String keyword, String metalake) throws Exception {
    URI uri =
        new URIBuilder(url + "/api/search/query")
            .addParameter("metalake", metalake)
            .addParameter("keyword", keyword)
            .addParameter("pageNumber", "0")
            .addParameter("pageSize", "10000")
            .build();

    HttpGet get = new HttpGet(uri);
    String json = client.execute(get, response -> EntityUtils.toString(response.getEntity()));
    return parseSearchResponse(mapper, json);
  }

  static List<SearchEntitiesDTO> parseSearchResponse(ObjectMapper mapper, String json)
      throws JsonProcessingException {
    JsonNode response = mapper.readTree(json);
    List<SearchEntitiesDTO> result = new ArrayList<>();
    for (JsonNode group : response.path("entities")) {
      EntityType entityType = mapper.treeToValue(group.path("type"), EntityType.class);
      Class<? extends SearchEntityDTO> entityClass = ENTITY_TYPE_TO_CLASS_DTO.get(entityType);
      if (entityClass == null) {
        throw new IllegalArgumentException("Unsupported search entity type: " + entityType);
      }

      List<SearchEntityDTO> entities = new ArrayList<>();
      for (JsonNode entity : group.path("entities")) {
        entities.add(mapper.treeToValue(entity, entityClass));
      }

      result.add(
          SearchEntitiesDTO.builder()
              .withTotalSize(group.path("totalSize").asInt())
              .withType(entityType)
              .withEntities(entities)
              .build());
    }
    return result;
  }

  public String rebuildIndex(String metalake) throws Exception {
    URI uri =
        new URIBuilder(url + String.format("/api/search/rebuild/metalakes/%s", metalake)).build();

    HttpPost post = new HttpPost(uri);
    String json =
        client.execute(
            post,
            response -> {
              if (response.getCode() != 200) {
                throw new RuntimeException("Failed to rebuild index");
              }
              return EntityUtils.toString(response.getEntity());
            });
    SyncMetadataResponse responseObj = mapper.readValue(json, SyncMetadataResponse.class);
    return responseObj.getTaskId();
  }

  public TaskStatusDTO getTaskStatus(String taskId) throws Exception {
    URI uri = new URIBuilder(url + String.format("/api/search/task/%s/status", taskId)).build();

    HttpGet get = new HttpGet(uri);
    String json = client.execute(get, response -> EntityUtils.toString(response.getEntity()));
    TaskStatusResponse responseObj = mapper.readValue(json, TaskStatusResponse.class);
    return responseObj.getTaskStatusDTO();
  }

  public void close() {
    try {
      client.close();
    } catch (Exception e) {
      throw new RuntimeException("Failed to close HTTP client", e);
    }
  }

  public static void main(String[] args) throws Exception {
    SearchClient client = new SearchClient("http://localhost:8090");
    client.search("", "test_metalake");
    client.search("c1", "test_metalake");
  }
}
