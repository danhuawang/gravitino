/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.po;

import com.datastrato.gravitino.search.po.SearchEntityPO.SearchEntityPOBuilder;
import com.google.common.collect.Lists;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSearchEntityPO {

  @Test
  void testSearchEntityPO() {
    SearchEntityPO searchEntityPO =
        SearchEntityPOBuilder.builder()
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
                    SearchEntityPO.SearchTagPO.builder().withTagName("good").build()))
            .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().build())
            .withOwner("owner")
            .withUserPermissions(null)
            .withRolePermissions(null)
            .build();
    Assertions.assertNotNull(searchEntityPO);
  }

  @Test
  void testMetalakeLevelTagDoesNotRequireCatalogName() {
    SearchEntityPO tag =
        SearchEntityPOBuilder.builder()
            .withEntityId(2L)
            .withEntityType(EntityType.TAG)
            .withInUse(true)
            .withMetalake("metalake")
            .withEntityName("sensitive")
            .withFullQualifiedName("sensitive")
            .build();

    Assertions.assertNull(tag.getCatalogName());
  }

  @Test
  void testSearchEntityWithException() {
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityPOBuilder.builder()
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
                          SearchEntityPO.SearchTagPO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("entityId must be positive"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityPOBuilder.builder()
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
                          SearchEntityPO.SearchTagPO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("entityType cannot be null"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityPOBuilder.builder()
                  .withEntityId(1L)
                  .withEntityType(EntityType.SCHEMA)
                  .withInUse(true)
                  .withMetalake("")
                  .withEntityName("entityName")
                  .withEntityComment("entityComment")
                  .withCatalogName("catalogName")
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(
                      Lists.newArrayList(
                          SearchEntityPO.SearchTagPO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("metalake cannot be blank"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityPOBuilder.builder()
                  .withEntityId(1L)
                  .withEntityType(EntityType.SCHEMA)
                  .withInUse(true)
                  .withMetalake("test")
                  .withEntityName(null)
                  .withEntityComment("entityComment")
                  .withCatalogName("catalogName")
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(
                      Lists.newArrayList(
                          SearchEntityPO.SearchTagPO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("entityName cannot be blank"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchEntityPOBuilder.builder()
                  .withEntityId(1L)
                  .withEntityType(EntityType.SCHEMA)
                  .withInUse(true)
                  .withMetalake("test")
                  .withEntityName("entityName")
                  .withEntityComment("entityComment")
                  .withCatalogName(null)
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(
                      Lists.newArrayList(
                          SearchEntityPO.SearchTagPO.builder().withTagName("good").build()))
                  .withSearchAudit(SearchEntityPO.SearchAuditPO.builder().build())
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });

    Assertions.assertTrue(exception.getMessage().contains("catalogName cannot be blank"));
  }
}
