/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.po;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.dto.SearchTableEntityDTO.SearchColumnDTO;
import com.datastrato.gravitino.search.dto.SearchViewEntityDTO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO.SearchColumn;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.google.common.collect.ImmutableList;
import org.apache.gravitino.Entity;
import org.junit.jupiter.api.Test;

class TestSearchViewEntityPO {

  private final SearchEntityCodec codec = new SearchEntityCodec();

  @Test
  void testBuildViewEntityPO() {
    SearchViewEntityPO po = newViewPO();

    assertEquals(Entity.EntityType.VIEW, po.getEntityType());
    assertEquals("c1.s1.v1", po.getFullQualifiedName());
    assertEquals(1, po.getColumns().size());
    assertEquals("id", po.getColumns().get(0).getColumnName());
  }

  @Test
  void testViewEntityPOAllowsEmptyColumns() {
    SearchViewEntityPO po =
        SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
            .withEntityId(1)
            .withEntityType(Entity.EntityType.VIEW)
            .withMetalake("test")
            .withCatalogName("c1")
            .withEntityName("v1")
            .withFullQualifiedName("c1.s1.v1")
            .withColumns(ImmutableList.of())
            .build();

    assertTrue(po.getColumns().isEmpty());
  }

  @Test
  void testViewEntityPORejectsInvalidEntity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
                .withEntityId(1)
                .withEntityType(Entity.EntityType.VIEW)
                .withMetalake("")
                .withCatalogName("c1")
                .withEntityName("v1")
                .build());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
                .withEntityId(0)
                .withEntityType(Entity.EntityType.VIEW)
                .withMetalake("test")
                .withCatalogName("c1")
                .withEntityName("v1")
                .build());
  }

  @Test
  void testSerializeAndConvertToDTO() {
    SearchViewEntityPO po = newViewPO();

    String json = codec.serialize(po);
    assertTrue(json.contains("\"entity_type\":\"view\""), json);
    assertTrue(json.contains("\"column_name\":\"id\""), json);

    SearchViewEntityPO deserialized = codec.deserialize(json, SearchViewEntityPO.class);
    assertEquals(po.getEntityId(), deserialized.getEntityId());
    assertEquals(1, deserialized.getColumns().size());

    SearchViewEntityDTO dto = codec.convert(deserialized, SearchViewEntityDTO.class);
    assertEquals(po.getEntityId(), dto.getEntityId());
    assertEquals(po.getEntityName(), dto.getEntityName());
    assertEquals(Entity.EntityType.VIEW, dto.getEntityType());
    assertEquals(po.getFullQualifiedName(), dto.getFullQualifiedName());

    SearchColumnDTO columnDTO = dto.getColumns().get(0);
    assertEquals("id", columnDTO.getColumnName());
    assertEquals("the id", columnDTO.getColumnComment());
  }

  @Test
  void testViewDefinitionIsNotIndexed() {
    // The view definition is deliberately not indexed, see SearchViewEntityPO. Guard the contract
    // so that the SQL body cannot reappear in an unfiltered search response by accident.
    String json = codec.serialize(newViewPO());

    assertFalse(json.contains("representations"), json);
    assertFalse(json.contains("default_catalog"), json);
    assertFalse(json.contains("default_schema"), json);
  }

  private SearchViewEntityPO newViewPO() {
    return SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
        .withEntityId(100)
        .withEntityType(Entity.EntityType.VIEW)
        .withInUse(true)
        .withMetalake("test")
        .withCatalogName("c1")
        .withEntityName("v1")
        .withEntityComment("demo view")
        .withFullQualifiedName("c1.s1.v1")
        .withColumns(
            ImmutableList.of(
                SearchColumn.builder().withColumnName("id").withColumnComment("the id").build()))
        .build();
  }
}
