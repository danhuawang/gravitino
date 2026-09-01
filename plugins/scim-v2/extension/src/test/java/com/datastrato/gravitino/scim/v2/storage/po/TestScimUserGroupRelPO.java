/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.po;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScimUserGroupRelPO {

  @Test
  void testBuilder() {
    ScimUserGroupRelPO relation =
        ScimUserGroupRelPO.builder()
            .withId(1L)
            .withUserId(100L)
            .withGroupId(200L)
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build();

    Assertions.assertEquals(1L, relation.getId());
    Assertions.assertEquals(100L, relation.getUserId());
    Assertions.assertEquals(200L, relation.getGroupId());
    Assertions.assertEquals(1L, relation.getCurrentVersion());
    Assertions.assertEquals(0L, relation.getLastVersion());
    Assertions.assertEquals(0L, relation.getDeletedAt());
  }

  @Test
  void testEqualsAndHashCode() {
    ScimUserGroupRelPO relation1 =
        ScimUserGroupRelPO.builder()
            .withId(1L)
            .withUserId(100L)
            .withGroupId(200L)
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build();

    ScimUserGroupRelPO relation2 =
        ScimUserGroupRelPO.builder()
            .withId(1L)
            .withUserId(100L)
            .withGroupId(200L)
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build();

    Assertions.assertEquals(relation1, relation2);
    Assertions.assertEquals(relation1.hashCode(), relation2.hashCode());
  }

  @Test
  void testBuilderReuse() {
    var builder =
        ScimUserGroupRelPO.builder()
            .withId(1L)
            .withUserId(100L)
            .withGroupId(200L)
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L);

    ScimUserGroupRelPO firstRelation = builder.build();
    ScimUserGroupRelPO secondRelation = builder.withGroupId(300L).build();

    Assertions.assertEquals(200L, firstRelation.getGroupId());
    Assertions.assertEquals(300L, secondRelation.getGroupId());
  }
}
