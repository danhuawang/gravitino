/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.dto.authorization.PrivilegeDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

class TestRolePrivilegeDTO {

  private static final Instant CREATED_AT = Instant.parse("2026-08-28T01:02:03Z");

  @Test
  void testRoundTrip() throws Exception {
    RolePrivilegeDTO rolePrivilege =
        RolePrivilegeDTO.builder()
            .withRole("admin")
            .withPrivileges(
                new PrivilegeDTO[] {
                  PrivilegeDTO.builder()
                      .withName(Privilege.Name.SELECT_TABLE)
                      .withCondition(Privilege.Condition.ALLOW)
                      .build()
                })
            .withCreateTime(CREATED_AT)
            .withAssignCount(3)
            .build();

    String json = JsonUtils.objectMapper().writeValueAsString(rolePrivilege);
    RolePrivilegeDTO roundTrip = JsonUtils.objectMapper().readValue(json, RolePrivilegeDTO.class);

    assertEquals("admin", roundTrip.role());
    assertEquals(CREATED_AT, roundTrip.getCreateTime());
    assertEquals(3, roundTrip.getAssignCount());
    assertEquals(1, roundTrip.privileges().size());
  }

  @Test
  void testRejectsInvalidMetadata() {
    PrivilegeDTO[] privileges =
        new PrivilegeDTO[] {
          PrivilegeDTO.builder()
              .withName(Privilege.Name.SELECT_TABLE)
              .withCondition(Privilege.Condition.ALLOW)
              .build()
        };

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RolePrivilegeDTO.builder()
                .withRole("admin")
                .withPrivileges(privileges)
                .withAssignCount(0)
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RolePrivilegeDTO.builder()
                .withRole("admin")
                .withPrivileges(privileges)
                .withCreateTime(CREATED_AT)
                .withAssignCount(-1)
                .build());
  }
}
