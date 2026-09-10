/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.ConditionBuilderVisitor;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestInMemorySearchStorage {
  private InMemorySearchStorage storage;

  @BeforeEach
  void setUp() {
    storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
  }

  @Test
  void testLiteralHyphens() {
    String boundaryName = "x".repeat(247) + "-boundary";
    String unicodeBoundaryName = "\uD83D\uDE00".repeat(128);
    String unicodeOverLimitName = "\uD83D\uDE00".repeat(129);
    Assertions.assertEquals(256, unicodeBoundaryName.length());
    Assertions.assertEquals(258, unicodeOverLimitName.length());
    List<String> names =
        List.of(
            unicodeBoundaryName,
            unicodeOverLimitName,
            boundaryName,
            "x" + boundaryName,
            "UPPER-NAME",
            "ac-db",
            "ac_db",
            "ac db",
            "ac-zzdb",
            "ac",
            "db",
            "c",
            "d",
            "builtin-sparkpi",
            "builtin-other",
            "other-sparkpi",
            "a*b",
            "aXb",
            "a?b",
            "a\"b",
            "a\\b");
    for (int i = 0; i < names.size(); i++) {
      storage.write(
          List.of(
              SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
                  .withEntityId(i + 1L)
                  .withEntityType(EntityType.TABLE)
                  .withMetalake("lake")
                  .withCatalogName("catalog")
                  .withEntityName(names.get(i))
                  .withEntityComment("comment-only")
                  .withFullQualifiedName(names.get(i))
                  .withColumns(
                      List.of(
                          SearchTableEntityPO.SearchColumn.builder().withColumnName("id").build()))
                  .build()),
          WriteContext.DEFAULT);
    }
    assertLiteralNames("ac", "ac-db", "ac_db", "ac db", "ac-zzdb", "ac");
    assertLiteralNames("-boundary", boundaryName);
    // OpenSearch ignore_above counts UTF-16 units, not Unicode code points.
    assertLiteralNames("\"\uD83D\uDE00\"", unicodeBoundaryName);
    assertLiteralNames("comment-only");
    Assertions.assertTrue(storage.search("lake", "comment-only", null, List.of(), 10, 0).isEmpty());
    Assertions.assertEquals(
        Set.of("ac-db"),
        storage.search("lake", "c-d", null, List.of(), 10, 0).stream()
            .flatMap(group -> group.getEntities().stream())
            .map(SearchEntityDTO::getEntityName)
            .collect(Collectors.toSet()));
    assertLiteralNames("c-", "ac-db", "ac-zzdb");
    assertLiteralNames("-d", "ac-db");
    assertLiteralNames("c-d", "ac-db");
    assertLiteralNames("AC-DB", "ac-db");
    assertLiteralNames("upper-name", "UPPER-NAME");
    assertLiteralNames("\".*\"");
    assertLiteralNames("\"a|b\"");
    assertLiteralNames("builtin-sparkpi", "builtin-sparkpi");
    assertLiteralNames("\"ac db\"", "ac db");
    assertLiteralNames("\"a*b\"", "a*b");
    assertLiteralNames("\"a?b\"", "a?b");
    assertLiteralNames("c- -name:ac-zzdb", "ac-db");
    assertLiteralNames("(c-d OR builtin-sparkpi) -name:builtin-sparkpi", "ac-db");
    assertLiteralNames("c-d builtin-sparkpi");
    assertLiteralNames("\"a\\\"b\"", "a\"b");
    assertLiteralNames("\"a\\\\b\"", "a\\b");
  }

  @Test
  void testUpdateMetalakeInUseOnlyChangesTargetMetalake() {
    storage.write(
        ImmutableList.of(catalog(1L, "metalake_a"), catalog(2L, "metalake_b")),
        WriteContext.DEFAULT);

    storage.updateMetalakeInUse("metalake_a", false);

    SearchEntityPO metalakeA = findByMetalake("metalake_a");
    SearchEntityPO metalakeB = findByMetalake("metalake_b");
    Assertions.assertFalse(metalakeA.isInUse());
    Assertions.assertTrue(metalakeB.isInUse());
    Assertions.assertInstanceOf(SearchCatalogEntityPO.class, metalakeA);
  }

  @Test
  void testDeleteMetalakeOnlyRemovesTargetMetalake() {
    storage.write(
        ImmutableList.of(catalog(1L, "metalake_a"), catalog(2L, "metalake_b")),
        WriteContext.DEFAULT);

    storage.deleteMetalake("metalake_a");

    Assertions.assertEquals(1, storage.getSearchEntities().size());
    Assertions.assertEquals("metalake_b", storage.getSearchEntities().get(0).getMetalake());
  }

  private SearchEntityPO findByMetalake(String metalake) {
    return storage.getSearchEntities().stream()
        .filter(entity -> metalake.equals(entity.getMetalake()))
        .findFirst()
        .orElseThrow();
  }

  private static SearchCatalogEntityPO catalog(long id, String metalake) {
    return SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.builder()
        .withEntityId(id)
        .withEntityType(EntityType.CATALOG)
        .withInUse(true)
        .withMetalake(metalake)
        .withEntityName("catalog")
        .withCatalogName("catalog")
        .withFullQualifiedName("catalog")
        .withProvider("hive")
        .withType(Catalog.Type.RELATIONAL)
        .build();
  }

  private void assertLiteralNames(String query, String... expected) {
    var result =
        storage.search(
            "lake",
            null,
            ConditionBuilderVisitor.buildQueryCondition(query).getCondition(),
            List.of(),
            100,
            0);
    Assertions.assertEquals(
        Set.of(expected),
        result.stream()
            .flatMap(group -> group.getEntities().stream())
            .map(SearchEntityDTO::getEntityName)
            .collect(Collectors.toSet()),
        query);
  }
}
