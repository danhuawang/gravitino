/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Covers sparse Role documents on the in-memory search backend. */
public class TestInMemoryRoleSearch {

  @Test
  void testSearchRoleWithoutCatalogCommentOrQualifiedName() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
    storage.write(ImmutableList.of(newRole()), WriteContext.DEFAULT);

    List<SearchEntitiesDTO> result =
        storage.search("test", "reader", null, ImmutableList.of(), 10, 0);

    assertEquals(1, result.size());
    assertEquals(EntityType.ROLE, result.get(0).getType());
    SearchEntityDTO role = result.get(0).getEntities().get(0);
    assertEquals("table_reader", role.getEntityName());
    assertNull(role.getEntityComment());
    assertNull(role.getCatalogName());
    assertNull(role.getFullQualifiedName());
  }

  @Test
  void testSparseRoleDoesNotBreakQualifiedNamePropertyOrTagFilters() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
    storage.write(ImmutableList.of(newRole()), WriteContext.DEFAULT);

    assertDoesNotThrow(
        () ->
            storage.search(
                "test",
                null,
                new Condition.PrefixCondition("full_qualified_name.keyword", "catalog."),
                ImmutableList.of(),
                10,
                0));
    assertDoesNotThrow(
        () ->
            storage.search(
                "test",
                null,
                new Condition.InCondition("tag_name", ImmutableList.of("pii")),
                ImmutableList.of(),
                10,
                0));
  }

  private SearchEntityPO newRole() {
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(12L)
        .withEntityType(EntityType.ROLE)
        .withMetalake("test")
        .withEntityName("table_reader")
        .withUpdateTime(1L)
        .build();
  }
}
