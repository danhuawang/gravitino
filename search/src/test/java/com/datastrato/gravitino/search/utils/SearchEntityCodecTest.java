/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.search.dto.SearchCatalogEntityDTO;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
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
}
