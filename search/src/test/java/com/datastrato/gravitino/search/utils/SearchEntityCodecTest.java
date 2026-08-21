/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.search.dto.SearchCatalogEntityDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchEntityCodecTest {

  private SearchEntityCodec searchEntityCodec;

  @BeforeEach
  void setUp() {
    searchEntityCodec = new SearchEntityCodec();
  }

  @Test
  void testSerializeSuccess() {
    SearchCatalogEntityPO po =
        SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.builder()
            .withEntityId(100)
            .withEntityName("TestEntity")
            .withEntityType(Entity.EntityType.CATALOG)
            .withMetalake("test")
            .withCatalogName("c1")
            .withProvider("hive")
            .withInUse(true)
            .withType(Catalog.Type.RELATIONAL)
            .build();
    String expectedJson =
        "{"
            + "\"entity_id\":100,"
            + "\"entity_type\":\"catalog\","
            + "\"in_use\":true,"
            + "\"metalake\":\"test\","
            + "\"entity_name\":\"TestEntity\","
            + "\"catalog_name\":\"c1\","
            + "\"update_time\":0,"
            + "\"provider\":\"hive\","
            + "\"type\":\"relational\""
            + "}";
    String result = searchEntityCodec.serialize(po);
    assertEquals(expectedJson, result);

    SearchCatalogEntityPO po1 =
        searchEntityCodec.deserialize(expectedJson, SearchCatalogEntityPO.class);
    SearchCatalogEntityDTO dto = searchEntityCodec.convert(po1, SearchCatalogEntityDTO.class);

    assertEquals(dto.getEntityId(), po.getEntityId());
    assertEquals(dto.getCatalogName(), po.getCatalogName());
    assertEquals(dto.getEntityName(), po.getEntityName());
    assertEquals(dto.getEntityType(), po.getEntityType());
    assertEquals(dto.getMetalake(), po.getMetalake());
    assertEquals(dto.isInUse(), po.isInUse());
    assertEquals(dto.getEntityType(), po.getEntityType());
  }

  @Test
  void testSparseUserRoundTrip() {
    SearchEntityPO user =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(101L)
            .withEntityType(Entity.EntityType.USER)
            .withMetalake("test")
            .withEntityName("alice")
            .withUpdateTime(10L)
            .build();

    String json = searchEntityCodec.serialize(user);
    SearchEntityPO deserialized = searchEntityCodec.deserialize(json, SearchEntityPO.class);
    SearchEntityDTO dto = searchEntityCodec.convert(deserialized, SearchEntityDTO.class);

    assertEquals(Entity.EntityType.USER, dto.getEntityType());
    assertEquals("alice", dto.getEntityName());
    assertNull(dto.getCatalogName());
    assertNull(dto.getEntityComment());
    assertNull(dto.getFullQualifiedName());
  }

  @Test
  void testSparseGroupRoundTrip() {
    SearchEntityPO group =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(102L)
            .withEntityType(Entity.EntityType.GROUP)
            .withMetalake("test")
            .withEntityName("engineers")
            .withUpdateTime(10L)
            .build();

    String json = searchEntityCodec.serialize(group);
    SearchEntityPO deserialized = searchEntityCodec.deserialize(json, SearchEntityPO.class);
    SearchEntityDTO dto = searchEntityCodec.convert(deserialized, SearchEntityDTO.class);

    assertEquals(Entity.EntityType.GROUP, dto.getEntityType());
    assertEquals("engineers", dto.getEntityName());
    assertNull(dto.getCatalogName());
    assertNull(dto.getEntityComment());
    assertNull(dto.getFullQualifiedName());
  }
}
