/*
 * Copyright 2026 Datastrato Pvt Ltd.
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

/** Covers sparse User documents on the in-memory search backend. */
public class TestInMemoryUserSearch {

  @Test
  void testSearchUserWithoutCatalogCommentOrQualifiedName() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
    storage.write(ImmutableList.of(newUser()), WriteContext.DEFAULT);

    List<SearchEntitiesDTO> result =
        storage.search("test", "alice", null, ImmutableList.of(), 10, 0);

    assertEquals(1, result.size());
    assertEquals(EntityType.USER, result.get(0).getType());
    SearchEntityDTO user = result.get(0).getEntities().get(0);
    assertEquals("alice_analyst", user.getEntityName());
    assertNull(user.getEntityComment());
    assertNull(user.getCatalogName());
    assertNull(user.getFullQualifiedName());
  }

  @Test
  void testSparseUserDoesNotBreakQualifiedNameOrTagFilters() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
    storage.write(ImmutableList.of(newUser()), WriteContext.DEFAULT);

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

  private SearchEntityPO newUser() {
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(10L)
        .withEntityType(EntityType.USER)
        .withMetalake("test")
        .withEntityName("alice_analyst")
        .withUpdateTime(1L)
        .build();
  }
}
