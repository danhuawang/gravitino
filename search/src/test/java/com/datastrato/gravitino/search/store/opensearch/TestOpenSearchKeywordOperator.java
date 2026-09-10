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
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.core.SearchRequest;

/** Verifies literal keyword routing and ordinary full-text operators across entity types. */
class TestOpenSearchKeywordOperator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void testOrdinaryKeywordsKeepOrOperator() throws IOException {
    for (EntityType type :
        List.of(
            EntityType.FILESET, EntityType.TABLE, EntityType.CATALOG, EntityType.JOB_TEMPLATE)) {
      assertEquals("or", bestFieldsOperator("sparkpi", type));
    }
  }

  @Test
  void testHyphenatedKeywordsUseLiteralMatching() throws IOException {
    for (EntityType type :
        List.of(
            EntityType.FILESET, EntityType.TABLE, EntityType.CATALOG, EntityType.JOB_TEMPLATE)) {
      SearchRequest request =
          OpenSearchStorage.createSearchRequestBuilder("daily-etl", null, List.of(), type).build();
      JsonNode query = OBJECT_MAPPER.readTree(request.toJsonString()).path("query");
      assertTrue(query.findValues("multi_match").isEmpty());
      List<JsonNode> names = query.findValues("entity_name.keyword");
      assertEquals(1, names.size());
      assertEquals("*daily-etl*", names.get(0).path("value").asText());
      assertTrue(names.get(0).path("case_insensitive").asBoolean());
      if (type == EntityType.JOB_TEMPLATE || type == EntityType.CATALOG) {
        assertFalse(query.toString().contains("entity_properties"));
      }
    }
  }

  private static String bestFieldsOperator(String keyword, EntityType entityType)
      throws IOException {
    SearchRequest request =
        OpenSearchStorage.createSearchRequestBuilder(keyword, null, ImmutableList.of(), entityType)
            .build();
    List<JsonNode> matches = new ArrayList<>();
    collectBestFields(OBJECT_MAPPER.readTree(request.toJsonString()), matches);
    assertEquals(1, matches.size(), "expected exactly one best_fields multi_match");
    // OpenSearch omits the default operator, so an absent field means "or".
    return matches.get(0).path("operator").asText("or");
  }

  private static void collectBestFields(JsonNode node, List<JsonNode> matches) {
    JsonNode multiMatch = node.path("multi_match");
    if (multiMatch.isObject() && "best_fields".equals(multiMatch.path("type").asText())) {
      matches.add(multiMatch);
    }
    node.forEach(child -> collectBestFields(child, matches));
  }
}
