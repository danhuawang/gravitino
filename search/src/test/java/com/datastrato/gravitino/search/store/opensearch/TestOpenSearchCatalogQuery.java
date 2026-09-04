/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.core.SearchRequest;

class TestOpenSearchCatalogQuery {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void testCatalogQueriesDoNotReadOrSearchProperties() throws IOException {
    SearchRequest catalogRequest =
        OpenSearchStorage.createSearchRequestBuilder(
                "secret", null, ImmutableList.of(), EntityType.CATALOG)
            .build();
    JsonNode catalogJson = OBJECT_MAPPER.readTree(catalogRequest.toJsonString());

    assertEquals(
        ImmutableList.of("entity_properties"), catalogRequest.source().filter().excludes());
    assertFalse(catalogJson.path("query").toString().contains("entity_properties"));

    SearchRequest requestedProperties =
        OpenSearchStorage.createSearchRequestBuilder(
                "secret", null, ImmutableList.of("entity_properties"), EntityType.CATALOG)
            .build();
    assertEquals(
        ImmutableList.of("entity_properties"), requestedProperties.source().filter().excludes());

    SearchRequest tableRequest =
        OpenSearchStorage.createSearchRequestBuilder(
                "secret", null, ImmutableList.of(), EntityType.TABLE)
            .build();
    JsonNode tableJson = OBJECT_MAPPER.readTree(tableRequest.toJsonString());
    assertTrue(tableJson.path("query").toString().contains("entity_properties"));
  }
}
