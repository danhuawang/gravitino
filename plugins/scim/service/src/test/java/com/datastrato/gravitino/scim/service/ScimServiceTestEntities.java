/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import java.time.Instant;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.UserEntity;

/** Test entity builders for SCIM service unit tests. */
public final class ScimServiceTestEntities {

  private ScimServiceTestEntities() {}

  public static UserEntity user(long id, String name, String externalId, boolean enabled) {
    Instant now = Instant.now();
    return user(id, name, externalId, enabled, now, now);
  }

  public static UserEntity user(
      long id,
      String name,
      String externalId,
      boolean enabled,
      Instant createTime,
      Instant lastModifiedTime) {
    return UserEntity.builder()
        .withId(id)
        .withName(name)
        .withExternalId(externalId)
        .withEnabled(enabled)
        .withAuditInfo(testAuditInfo(createTime, lastModifiedTime))
        .build();
  }

  public static org.apache.gravitino.meta.GroupEntity group(
      long id, String name, String externalId) {
    Instant now = Instant.now();
    return group(id, name, externalId, now, now);
  }

  public static org.apache.gravitino.meta.GroupEntity group(
      long id, String name, String externalId, Instant createTime, Instant lastModifiedTime) {
    return org.apache.gravitino.meta.GroupEntity.builder()
        .withId(id)
        .withName(name)
        .withExternalId(externalId)
        .withAuditInfo(testAuditInfo(createTime, lastModifiedTime))
        .build();
  }

  private static AuditInfo testAuditInfo(Instant createTime, Instant lastModifiedTime) {
    return AuditInfo.builder()
        .withCreator("test")
        .withCreateTime(createTime)
        .withLastModifier("test")
        .withLastModifiedTime(lastModifiedTime)
        .build();
  }
}
