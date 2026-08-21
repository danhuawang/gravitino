/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
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

/** Covers sparse Group documents on the in-memory search backend. */
public class TestInMemoryGroupSearch {

  @Test
  void testSearchGroupWithoutCatalogCommentOrQualifiedName() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
    storage.write(ImmutableList.of(newGroup()), WriteContext.DEFAULT);

    List<SearchEntitiesDTO> result =
        storage.search("test", "engineer", null, ImmutableList.of(), 10, 0);

    assertEquals(1, result.size());
    assertEquals(EntityType.GROUP, result.get(0).getType());
    SearchEntityDTO group = result.get(0).getEntities().get(0);
    assertEquals("data_engineers", group.getEntityName());
    assertNull(group.getEntityComment());
    assertNull(group.getCatalogName());
    assertNull(group.getFullQualifiedName());
  }

  @Test
  void testSparseGroupDoesNotBreakQualifiedNameOrTagFilters() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
    storage.write(ImmutableList.of(newGroup()), WriteContext.DEFAULT);

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

  private SearchEntityPO newGroup() {
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(11L)
        .withEntityType(EntityType.GROUP)
        .withMetalake("test")
        .withEntityName("data_engineers")
        .withUpdateTime(1L)
        .build();
  }
}
