/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.po;

import com.google.common.collect.Lists;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSearchTableEntityPO {

  @Test
  void testSearchTableEntityPO() {
    SearchTableEntityPO searchTableEntityPO =
        SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
            .withColumns(
                Lists.newArrayList(
                    new SearchTableEntityPO.SearchColumn.Builder()
                        .withColumnName("columnName")
                        .withColumnComment("columnComment")
                        .build()))
            .withSearchAudit(null)
            .withEntityId(1L)
            .withEntityType(EntityType.SCHEMA)
            .withInUse(true)
            .withMetalake("metalake")
            .withEntityName("entityName")
            .withEntityComment("entityComment")
            .withCatalogName("catalogName")
            .withFullQualifiedName("fullQualifiedName")
            .withTags(null)
            .withOwner("owner")
            .withUserPermissions(null)
            .withRolePermissions(null)
            .build();
  }

  @Test
  void testSearchTableEntityPOWithException() {
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
                  .withColumns(
                      Lists.newArrayList(
                          new SearchTableEntityPO.SearchColumn.Builder()
                              .withColumnName("")
                              .withColumnComment("columnComment")
                              .build()))
                  .withEntityId(1L)
                  .withEntityType(EntityType.SCHEMA)
                  .withInUse(true)
                  .withMetalake("metalake")
                  .withEntityName("entityName")
                  .withEntityComment("entityComment")
                  .withCatalogName("catalogName")
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(null)
                  .withSearchAudit(null)
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });

    Assertions.assertTrue(
        exception.getMessage().contains("\"columnName\" cannot be null or empty"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
                  .withColumns(Lists.newArrayList())
                  .withEntityId(1L)
                  .withEntityType(EntityType.SCHEMA)
                  .withInUse(true)
                  .withMetalake("metalake")
                  .withEntityName("entityName")
                  .withEntityComment("entityComment")
                  .withCatalogName("catalogName")
                  .withFullQualifiedName("fullQualifiedName")
                  .withTags(null)
                  .withSearchAudit(null)
                  .withOwner("owner")
                  .withUserPermissions(null)
                  .withRolePermissions(null)
                  .build();
            });
    Assertions.assertTrue(exception.getMessage().contains("\"columns\" cannot be null or empty"));
  }
}
