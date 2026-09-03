/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Collections;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.GroupDTO;
import org.junit.jupiter.api.Test;

public class TestExtendedGroupDTO {

  @Test
  public void testFromIncludesUserCount() {
    Group group = buildGroup("analysts");
    ExtendedGroupDTO dto = ExtendedGroupDTO.from(group, IdentitySource.LOCAL, 12);
    assertEquals("analysts", dto.name());
    assertEquals(IdentitySource.LOCAL, dto.origin());
    assertEquals(12, dto.userCount());
  }

  @Test
  public void testFromDefaultsUserCountToZero() {
    ExtendedGroupDTO dto =
        ExtendedGroupDTO.from(buildGroup("empty"), IdentitySource.PROVISIONED, 0);
    assertEquals(IdentitySource.PROVISIONED, dto.origin());
    assertEquals(0, dto.userCount());
  }

  @Test
  public void testFromJitOrigin() {
    ExtendedGroupDTO dto = ExtendedGroupDTO.from(buildGroup("agents"), IdentitySource.JIT, 0);
    assertEquals(IdentitySource.JIT, dto.origin());
    assertEquals(0, dto.userCount());
  }

  private static Group buildGroup(String name) {
    return GroupDTO.builder()
        .withId(1L)
        .withName(name)
        .withRoles(Collections.emptyList())
        .withAudit(AuditDTO.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
