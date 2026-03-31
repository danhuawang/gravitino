/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.search.dto;

import com.google.common.collect.Lists;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSearchCatalogEntityDTO {

  @Test
  void testSearchCatalogEntity() {
    SearchCatalogEntityDTO searchCatalogEntityDTO =
        SearchCatalogEntityDTO.SearchCatalogEntityDTOBuilder.builder()
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("provider")
            .withEntityId(1L)
            .withEntityType(EntityType.CATALOG)
            .withInUse(true)
            .withMetalake("metalake")
            .withEntityName("entityName")
            .withEntityComment("entityComment")
            .withCatalogName("catalogName")
            .withFullQualifiedName("fullQualifiedName")
            .withTags(
                Lists.newArrayList(
                    SearchEntityDTO.SearchTagDTO.builder().withTagName("good").build()))
            .withSearchAudit(SearchEntityDTO.SearchAuditDTO.builder().build())
            .withOwner("owner")
            .withUserPermissions(null)
            .withRolePermissions(null)
            .build();
    Assertions.assertNotNull(searchCatalogEntityDTO);
  }

  @Test
  void tesSearchCatalogEntityDTOWithException() {
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchCatalogEntityDTO.SearchCatalogEntityDTOBuilder.builder()
                  .withEntityId(1L)
                  .withEntityType(EntityType.CATALOG)
                  .withType(null)
                  .withProvider("provider")
                  .withInUse(true)
                  .withMetalake("metalake")
                  .withEntityName("entityName")
                  .withEntityComment("entityComment")
                  .withCatalogName("catalogName")
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(
                      Lists.newArrayList(
                          SearchEntityDTO.SearchTagDTO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityDTO.SearchAuditDTO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("\"type\" cannot be null"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchCatalogEntityDTO.SearchCatalogEntityDTOBuilder.builder()
                  .withType(Catalog.Type.RELATIONAL)
                  .withEntityId(1L)
                  .withEntityType(EntityType.CATALOG)
                  .withInUse(true)
                  .withMetalake("metalake")
                  .withEntityName("entityName")
                  .withEntityComment("entityComment")
                  .withCatalogName("catalogName")
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(
                      Lists.newArrayList(
                          SearchEntityDTO.SearchTagDTO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityDTO.SearchAuditDTO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("\"provider\" cannot be blank"));
  }
}
