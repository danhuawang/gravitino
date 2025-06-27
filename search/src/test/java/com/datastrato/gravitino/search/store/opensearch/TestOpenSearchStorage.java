/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchTableEntityDTO;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestOpenSearchStorage {
  private OpenSearchStorage storage;
  private OpenSearchContainer container;

  @BeforeAll
  public void initTest() throws IllegalAccessException {

    this.container =
        new OpenSearchContainer(
            "ci-opensearch",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                "axzin1S3?@A"));
    container.start();

    this.storage = new OpenSearchStorage();
    OpenSearchConfig config =
        new OpenSearchConfig(
            ImmutableMap.of(
                OpenSearchConfig.OPEN_SEARCH_URL_KEY, container.getOpenSearchUrl(),
                OpenSearchConfig.OPEN_SEARCH_USERNAME_KEY, OpenSearchContainer.DEFAULT_USERNAME,
                OpenSearchConfig.OPEN_SEARCH_PASSWORD_KEY, OpenSearchContainer.DEFAULT_PASSWORD));
    this.storage.initialize(config);
  }

  @Test
  void testSynchronizeMetadata() throws Exception {
    try {
      SearchEntityPO tablePO1 =
          SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
              .withEntityId(100)
              .withEntityName("table1")
              .withFullQualifiedName("c1.s1.table1")
              .withEntityType(Entity.EntityType.TABLE)
              .withMetalake("test")
              .withCatalogName("c1")
              .withEntityComment("demo table")
              .withColumns(
                  ImmutableList.of(
                      SearchTableEntityPO.SearchColumn.builder()
                          .withColumnName("f1")
                          .withColumnComment("test column 1")
                          .build()))
              .build();
      storage.write(ImmutableList.of(tablePO1), true);

      List<SearchEntitiesDTO> result =
          storage.search("test", null, null, ImmutableList.of(), 10, 0);
      SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, Entity.EntityType.TABLE);
      Assertions.assertNotNull(dto);
      Assertions.assertEquals(1, dto.getTotalSize());
      SearchTableEntityDTO tableDTO = (SearchTableEntityDTO) dto.getEntities().get(0);
      Assertions.assertEquals(tableDTO.getCatalogName(), tablePO1.getCatalogName());
      Assertions.assertEquals(tableDTO.getFullQualifiedName(), tablePO1.getFullQualifiedName());

      SearchEntityPO schemaPO =
          SearchEntityPO.SearchEntityPOBuilder.builder()
              .withEntityId(200)
              .withEntityName("schema1")
              .withFullQualifiedName("c1.s1")
              .withEntityType(Entity.EntityType.SCHEMA)
              .withMetalake("test")
              .withCatalogName("c1")
              .withEntityComment("demo schema")
              .build();
      storage.write(ImmutableList.of(schemaPO), true);
      result = storage.search("test", null, null, ImmutableList.of(), 10, 0);
      dto = getSearchEntitiesDTOByType(result, Entity.EntityType.SCHEMA);
      Assertions.assertNotNull(dto);
      Assertions.assertEquals(1, dto.getTotalSize());
      SearchEntityDTO schemaDTO = dto.getEntities().get(0);
      Assertions.assertEquals(tableDTO.getCatalogName(), tablePO1.getCatalogName());
      Assertions.assertEquals(schemaDTO.getFullQualifiedName(), schemaPO.getFullQualifiedName());

      SearchCatalogEntityPO catalogEntityPO =
          SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.builder()
              .withEntityId(300)
              .withEntityName("catalog1")
              .withFullQualifiedName("c1")
              .withEntityType(Entity.EntityType.CATALOG)
              .withMetalake("test")
              .withCatalogName("c1")
              .withEntityComment("demo catalog")
              .withType(Catalog.Type.RELATIONAL)
              .withProvider("hive")
              .build();
      storage.write(ImmutableList.of(catalogEntityPO), true);
      result = storage.search("test", null, null, ImmutableList.of(), 10, 0);
      dto = getSearchEntitiesDTOByType(result, Entity.EntityType.CATALOG);
      Assertions.assertNotNull(dto);
      Assertions.assertEquals(1, dto.getTotalSize());
      SearchEntityDTO catalogDTO = dto.getEntities().get(0);
      Assertions.assertEquals(catalogDTO.getCatalogName(), catalogEntityPO.getCatalogName());
      Assertions.assertEquals(
          catalogDTO.getFullQualifiedName(), catalogEntityPO.getFullQualifiedName());

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
