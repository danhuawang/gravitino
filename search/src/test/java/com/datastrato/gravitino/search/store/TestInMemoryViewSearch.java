/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchViewEntityDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO.SearchColumn;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Covers indexing and querying views on the in-memory search backend. */
public class TestInMemoryViewSearch {

  private InMemorySearchStorage storage;

  @BeforeEach
  void setUp() {
    storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));
  }

  @Test
  void testSearchViewByName() {
    storage.write(ImmutableList.of(newViewPO()), WriteContext.DEFAULT);

    SearchViewEntityDTO viewDTO = searchSingleView("daily_orders");
    Assertions.assertEquals("catalog.s1.daily_orders", viewDTO.getFullQualifiedName());
    Assertions.assertEquals("order_day", viewDTO.getColumns().get(0).getColumnName());
  }

  @Test
  void testSearchViewByCommentAndFullQualifiedName() {
    storage.write(ImmutableList.of(newViewPO()), WriteContext.DEFAULT);

    Assertions.assertEquals(
        "catalog.s1.daily_orders", searchSingleView("orders by day").getFullQualifiedName());
    Assertions.assertEquals(
        "catalog.s1.daily_orders", searchSingleView("catalog.s1").getFullQualifiedName());
  }

  @Test
  void testSearchViewByColumn() {
    storage.write(ImmutableList.of(newViewPO()), WriteContext.DEFAULT);

    // The OpenSearch backend matches column names and comments, the memory backend must agree.
    Assertions.assertEquals(
        "catalog.s1.daily_orders", searchSingleView("order_day").getFullQualifiedName());
    Assertions.assertEquals(
        "catalog.s1.daily_orders", searchSingleView("the order day").getFullQualifiedName());
  }

  @Test
  void testSearchViewWithoutMatchReturnsNothing() {
    storage.write(ImmutableList.of(newViewPO()), WriteContext.DEFAULT);

    Assertions.assertTrue(
        storage.search("metalake-a", "no_such_word", null, ImmutableList.of(), 10, 0).isEmpty());
  }

  @Test
  void testSearchViewWithoutCommentAndColumns() {
    // Neither the comment nor the columns are mandatory, a keyword lookup must not fail on them.
    SearchEntityPO viewPO =
        SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
            .withEntityId(2)
            .withEntityType(EntityType.VIEW)
            .withMetalake("metalake-a")
            .withCatalogName("catalog")
            .withEntityName("bare_view")
            .build();
    storage.write(ImmutableList.of(viewPO), WriteContext.DEFAULT);

    Assertions.assertEquals("bare_view", searchSingleView("bare_view").getEntityName());
    Assertions.assertTrue(
        storage.search("metalake-a", "order_day", null, ImmutableList.of(), 10, 0).isEmpty());
  }

  private SearchViewEntityDTO searchSingleView(String keyword) {
    List<SearchEntitiesDTO> result =
        storage.search("metalake-a", keyword, null, ImmutableList.of(), 10, 0);
    Assertions.assertEquals(1, result.size(), "No view matched the keyword " + keyword);
    Assertions.assertEquals(EntityType.VIEW, result.get(0).getType());
    return (SearchViewEntityDTO) result.get(0).getEntities().get(0);
  }

  private SearchEntityPO newViewPO() {
    return SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
        .withEntityId(1)
        .withEntityType(EntityType.VIEW)
        .withMetalake("metalake-a")
        .withCatalogName("catalog")
        .withEntityName("daily_orders")
        .withEntityComment("orders by day")
        .withFullQualifiedName("catalog.s1.daily_orders")
        .withColumns(
            ImmutableList.of(
                SearchColumn.builder()
                    .withColumnName("order_day")
                    .withColumnComment("the order day")
                    .build()))
        .build();
  }
}
