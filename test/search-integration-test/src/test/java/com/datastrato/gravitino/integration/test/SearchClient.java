/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.integration.test;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.rest.SearchQueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import org.apache.gravitino.client.ObjectMapperProvider;
import org.apache.hc.client5.http.classic.methods.HttpGet;
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
    try (var response = client.execute(get)) {
      String json = EntityUtils.toString(response.getEntity());
      SearchQueryResponse responseObj = mapper.readValue(json, SearchQueryResponse.class);
      return responseObj.getEntities();
    }
  }

  public void close() {
    try {
      client.close();
    } catch (Exception e) {
    }
  }

  public static void main(String[] args) throws Exception {
    SearchClient client = new SearchClient("http://localhost:8090");
    client.search("", "test_metalake");
    client.search("c1", "test_metalake");
  }
}
