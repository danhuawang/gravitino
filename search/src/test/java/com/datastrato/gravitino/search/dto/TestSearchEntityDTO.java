/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.dto;

import com.google.common.collect.Lists;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSearchEntityDTO {

  @Test
  void testSearchEntityDTO() {
    SearchEntityDTO searchEntityDTO =
        SearchEntityDTO.SearchEntityDTOBuilder.builder()
            .withEntityId(1L)
            .withEntityType(EntityType.SCHEMA)
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
  }

  @Test
  void testSearchTableEntityDTOWithException() {
    IllegalArgumentException exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityDTO.SearchEntityDTOBuilder.builder()
                  .withEntityId(-1L)
                  .withEntityType(EntityType.SCHEMA)
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
    Assertions.assertTrue(exception.getMessage().contains("entityId must be positive"));

    exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityDTO.SearchEntityDTOBuilder.builder()
                  .withEntityId(1L)
                  .withEntityType(null)
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
    Assertions.assertTrue(exception.getMessage().contains("entityType cannot be null"));

    exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityDTO.SearchEntityDTOBuilder.builder()
                  .withEntityId(1L)
                  .withEntityType(EntityType.SCHEMA)
                  .withInUse(true)
                  .withMetalake("")
                  .withEntityName("")
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
    Assertions.assertTrue(exception.getMessage().contains("metalake cannot be blank"));
  }
}
