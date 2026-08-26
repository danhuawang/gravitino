/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO.SearchColumn;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;
import org.apache.gravitino.Entity;
import org.junit.jupiter.api.Test;

/**
 * Guards against drift between the persistent objects written to OpenSearch and the index templates
 * that define how those documents are mapped. A mapping is declared with {@code "dynamic": false},
 * so a field missing from the template is silently unsearchable rather than rejected.
 */
class TestSearchDocumentFieldCoverage {

  private static final String TEMPLATE_VERSION = "v2";

  /**
   * Fields that every {@link SearchEntityPO} carries but that the v2 bundle deliberately leaves out
   * of the mappings introduced in that bundle. They stay in {@code _source}, so they are still
   * returned to callers; they are simply not searchable. Indexing them is tracked by
   * datastrato/gravitino-enterprise#1259 and requires a new bundle version.
   */
  private static final Set<String> UNMAPPED_BY_DESIGN =
      Set.of(
          "in_use",
          "owner",
          "user_permissions",
          "role_permissions",
          "tags.tag_comment",
          "tags.properties");

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final SearchEntityCodec codec = new SearchEntityCodec();

  @Test
  void testViewFieldsAreMapped() throws IOException {
    assertFieldsAreMapped("view", newViewPO());
  }

  private void assertFieldsAreMapped(String entityName, SearchEntityPO po) throws IOException {
    JsonNode mapping = readTemplate(entityName).path("mappings").path("properties");
    assertFieldsAreMapped("", MAPPER.readTree(codec.serialize(po)), mapping);
  }

  private void assertFieldsAreMapped(String path, JsonNode document, JsonNode mapping) {
    Iterator<String> fieldNames = document.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      String fieldPath = path.isEmpty() ? fieldName : path + "." + fieldName;
      JsonNode fieldMapping = mapping.path(fieldName);
      if (UNMAPPED_BY_DESIGN.contains(fieldPath)) {
        assertTrue(
            fieldMapping.isMissingNode(),
            "Field " + fieldPath + " is now mapped, drop it from UNMAPPED_BY_DESIGN");
        continue;
      }
      assertTrue(!fieldMapping.isMissingNode(), "Field " + fieldPath + " is not mapped");

      JsonNode value = document.path(fieldName);
      JsonNode nested = value.isArray() && value.size() > 0 ? value.get(0) : value;
      if (nested.isObject() && fieldMapping.has("properties")) {
        assertFieldsAreMapped(fieldPath, nested, fieldMapping.path("properties"));
      }
    }
  }

  private JsonNode readTemplate(String entityName) throws IOException {
    // The templates live in the root bin/ directory, tests run from the search/ module directory.
    File template =
        Paths.get(
                System.getProperty("user.dir"),
                "..",
                "bin",
                "opensearch",
                TEMPLATE_VERSION,
                entityName + "_entity_indices.json")
            .normalize()
            .toFile();
    assertTrue(template.exists(), "Missing index template " + template);
    return MAPPER.readTree(template);
  }

  private SearchViewEntityPO newViewPO() {
    return SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
        .withEntityId(1)
        .withEntityType(Entity.EntityType.VIEW)
        .withInUse(true)
        .withMetalake("test")
        .withCatalogName("c1")
        .withEntityName("v1")
        .withEntityComment("demo view")
        .withFullQualifiedName("c1.s1.v1")
        .withOwner("tester")
        .withTags(
            ImmutableList.of(
                SearchEntityPO.SearchTagPO.builder()
                    .withTagName("pii")
                    .withTagComment("sensitive")
                    .build()))
        .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().withCreator("tester").build())
        .withEntityProperties(ImmutableList.of(new SearchEntityPO.PropertyPO("k", "v")))
        .withColumns(
            ImmutableList.of(
                SearchColumn.builder().withColumnName("id").withColumnComment("the id").build()))
        .withUpdateTime(1L)
        .build();
  }
}
