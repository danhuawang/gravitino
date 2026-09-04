/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.google.common.collect.ImmutableList;
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
}
