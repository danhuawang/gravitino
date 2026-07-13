/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import java.time.Instant;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.UserEntity;

/** Test entity builders for SCIM service unit tests. */
public final class ScimServiceTestEntities {

  private ScimServiceTestEntities() {}

  public static UserEntity user(long id, String name, String externalId, boolean enabled) {
    return UserEntity.builder()
        .withId(id)
        .withName(name)
        .withExternalId(externalId)
        .withEnabled(enabled)
        .withAuditInfo(testAuditInfo())
        .build();
  }

  public static org.apache.gravitino.meta.GroupEntity group(
      long id, String name, String externalId) {
    return org.apache.gravitino.meta.GroupEntity.builder()
        .withId(id)
        .withName(name)
        .withExternalId(externalId)
        .withAuditInfo(testAuditInfo())
        .build();
  }

  private static AuditInfo testAuditInfo() {
    return AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();
  }
}
