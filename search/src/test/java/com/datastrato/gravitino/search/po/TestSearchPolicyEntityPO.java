/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.po;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.gravitino.Entity;
import org.junit.jupiter.api.Test;

class TestSearchPolicyEntityPO {

  @Test
  void testPolicyDoesNotRequireCatalogName() {
    SearchPolicyEntityPO policy =
        SearchPolicyEntityPO.Builder.builder()
            .withEntityId(1L)
            .withEntityType(Entity.EntityType.POLICY)
            .withInUse(true)
            .withMetalake("metalake")
            .withEntityName("retention")
            .withFullQualifiedName("retention")
            .withPolicyType("custom")
            .withEnabled(true)
            .withContent("{\"retentionDays\":30}")
            .build();

    assertEquals("custom", policy.getPolicyType());
    assertEquals("{\"retentionDays\":30}", policy.getContent());
  }

  @Test
  void testPolicyTypeIsRequired() {
    SearchPolicyEntityPO.Builder builder =
        SearchPolicyEntityPO.Builder.builder()
            .withEntityId(1L)
            .withEntityType(Entity.EntityType.POLICY)
            .withInUse(true)
            .withMetalake("metalake")
            .withEntityName("retention");

    assertThrows(IllegalArgumentException.class, builder::build);
  }
}
