/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.search.store.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestIndexTemplateMappings {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> V1_ENTITY_TYPES =
      Set.of("catalog", "fileset", "model", "schema", "table", "topic");
  private static final Set<String> NEW_ENTITY_TYPES =
      Set.of("user", "group", "role", "function", "view", "tag", "policy");

  @Test
  void testVersionManifests() throws IOException {
    Map<String, Path> v1Templates = loadManifest("v1");
    Map<String, Path> v2Templates = loadManifest("v2");

    Assertions.assertEquals(V1_ENTITY_TYPES, entityTypes(v1Templates));
    Assertions.assertEquals(
        Set.of(
            "catalog",
            "fileset",
            "model",
            "schema",
            "table",
            "topic",
            "user",
            "group",
            "role",
            "function",
            "view",
            "tag",
            "policy"),
        entityTypes(v2Templates));

    for (Path template : v1Templates.values()) {
      Assertions.assertDoesNotThrow(() -> OBJECT_MAPPER.readTree(template.toFile()));
    }
    for (Path template : v2Templates.values()) {
      Assertions.assertDoesNotThrow(() -> OBJECT_MAPPER.readTree(template.toFile()));
    }
  }

  @Test
  void testExistingMappingsAreUnchangedInV2() throws IOException {
    Map<String, Path> v1Templates = loadManifest("v1");
    Map<String, Path> v2Templates = loadManifest("v2");

    for (String entityType : V1_ENTITY_TYPES) {
      String pattern = entityType + "_entity_index";
      JsonNode v1Mapping = OBJECT_MAPPER.readTree(v1Templates.get(pattern).toFile());
      JsonNode v2Mapping = OBJECT_MAPPER.readTree(v2Templates.get(pattern).toFile());
      Assertions.assertEquals(v1Mapping, v2Mapping, entityType + " mapping changed in v2");
    }
  }

  @Test
  void testNewMappingsContainOnlySearchProjectionFields() throws IOException {
    Map<String, Set<String>> expectedFields = new LinkedHashMap<>();
    expectedFields.put("user", commonFields("entity_id", "entity_type", "metalake", "entity_name"));
    expectedFields.put(
        "group", commonFields("entity_id", "entity_type", "metalake", "entity_name"));
    expectedFields.put("role", commonFields("entity_id", "entity_type", "metalake", "entity_name"));
    // Tags carry searchable properties, so unlike the other metalake-level projections the tag
    // mapping also indexes "entity_properties".
    expectedFields.put(
        "tag",
        commonFields(
            "entity_id",
            "entity_type",
            "metalake",
            "entity_name",
            "entity_comment",
            "entity_properties"));
    expectedFields.put(
        "policy",
        commonFields("entity_id", "entity_type", "metalake", "entity_name", "entity_comment"));
    expectedFields.put(
        "function",
        commonFields(
            "entity_id",
            "entity_type",
            "metalake",
            "entity_name",
            "entity_comment",
            "catalog_name",
            "full_qualified_name",
            "tags"));
    expectedFields.put(
        "view",
        commonFields(
            "entity_id",
            "entity_type",
            "metalake",
            "entity_name",
            "entity_comment",
            "catalog_name",
            "full_qualified_name",
            "tags",
            "columns",
            "entity_properties"));

    Map<String, Path> v2Templates = loadManifest("v2");
    // The audit block is the one part every entity type shares, so it is pinned to the shape the
    // released v1 bundle already uses instead of being spelled out per entity type.
    Set<String> expectedAuditFields =
        searchAuditFields(
            OBJECT_MAPPER.readTree(loadManifest("v1").get("catalog_entity_index").toFile()));

    Assertions.assertEquals(NEW_ENTITY_TYPES, expectedFields.keySet());
    for (Map.Entry<String, Set<String>> expected : expectedFields.entrySet()) {
      JsonNode mapping =
          OBJECT_MAPPER.readTree(v2Templates.get(expected.getKey() + "_entity_index").toFile());
      Assertions.assertFalse(mapping.path("mappings").path("dynamic").asBoolean(true));
      Assertions.assertEquals(
          expected.getValue(),
          fieldNames(mapping.path("mappings").path("properties")),
          expected.getKey() + " contains unexpected search fields");
      Assertions.assertEquals(
          expectedAuditFields,
          searchAuditFields(mapping),
          expected.getKey() + " audit fields diverge from the v1 bundle");
    }
  }

  @Test
  void testViewAndFunctionAssociationsAreLightweight() throws IOException {
    Map<String, Path> v2Templates = loadManifest("v2");
    JsonNode function = OBJECT_MAPPER.readTree(v2Templates.get("function_entity_index").toFile());
    JsonNode view = OBJECT_MAPPER.readTree(v2Templates.get("view_entity_index").toFile());

    Assertions.assertEquals(
        Set.of("tag_name"),
        nestedFieldNames(function, "tags"),
        "Function tags should only store names used by search");
    Assertions.assertEquals(
        Set.of("tag_name"),
        nestedFieldNames(view, "tags"),
        "View tags should only store names used by search");
    Assertions.assertEquals(
        Set.of("column_name", "column_comment"),
        nestedFieldNames(view, "columns"),
        "View columns should only store searchable fields");
  }

  private static Map<String, Path> loadManifest(String version) throws IOException {
    Path versionDirectory = binDirectory().resolve("opensearch").resolve(version);
    Path manifest = versionDirectory.resolve("templates.list");
    Map<String, Path> templates = new LinkedHashMap<>();

    for (String line : Files.readAllLines(manifest)) {
      String entry = line.trim();
      if (entry.isEmpty() || entry.startsWith("#")) {
        continue;
      }

      String[] values = entry.split("\\s+");
      Assertions.assertEquals(2, values.length, "Invalid manifest entry: " + entry);
      Path template = versionDirectory.resolve(values[1]);
      Assertions.assertTrue(Files.isRegularFile(template), "Missing template: " + template);
      Assertions.assertNull(
          templates.put(values[0], template), "Duplicate template pattern: " + values[0]);
    }

    Assertions.assertFalse(templates.isEmpty(), "Template manifest must not be empty");
    return templates;
  }

  private static Path binDirectory() {
    Path userDirectory = Paths.get(System.getProperty("user.dir"));
    Path localBin = userDirectory.resolve("bin");
    if (Files.isDirectory(localBin)) {
      return localBin;
    }
    return userDirectory.resolve("..").resolve("bin").normalize();
  }

  private static Set<String> entityTypes(Map<String, Path> templates) {
    return templates.keySet().stream()
        .map(pattern -> pattern.replaceFirst("_entity_index$", ""))
        .collect(Collectors.toSet());
  }

  private static Set<String> commonFields(String... fields) {
    List<String> allFields = new ArrayList<>(List.of(fields));
    allFields.add("search_audit");
    allFields.add("update_time");
    return Set.copyOf(allFields);
  }

  private static Set<String> searchAuditFields(JsonNode mapping) {
    return nestedFieldNames(mapping, "search_audit");
  }

  private static Set<String> nestedFieldNames(JsonNode mapping, String field) {
    return fieldNames(mapping.path("mappings").path("properties").path(field).path("properties"));
  }

  private static Set<String> fieldNames(JsonNode properties) {
    Set<String> fields = new HashSet<>();
    properties.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
