/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.authorization.RoleGroupAssignmentDTO;
import com.datastrato.gravitino.dto.authorization.RoleUserAssignmentDTO;
import java.time.Instant;
import java.util.Collections;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.GroupDTO;
import org.apache.gravitino.dto.authorization.UserDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

class TestRolePrincipalAssignmentResponses {

  private static final Instant ASSIGNED_AT = Instant.parse("2026-08-28T01:02:03Z");

  @Test
  void testRoleUserAssignmentResponseRoundTrip() throws Exception {
    UserDTO user =
        UserDTO.builder()
            .withId(1L)
            .withName("alice")
            .withRoles(Collections.emptyList())
            .withAudit(buildEntityAudit())
            .build();
    RoleUserAssignmentListResponse response =
        new RoleUserAssignmentListResponse(
            new RoleUserAssignmentDTO[] {
              new RoleUserAssignmentDTO(user, buildAssignmentAudit(), true)
            });

    String json = JsonUtils.objectMapper().writeValueAsString(response);
    RoleUserAssignmentListResponse roundTrip =
        JsonUtils.objectMapper().readValue(json, RoleUserAssignmentListResponse.class);
    roundTrip.validate();

    assertEquals("alice", roundTrip.getUsers()[0].name());
    assertEquals(IdentitySource.LOCAL, roundTrip.getUsers()[0].origin());
    assertEquals(ASSIGNED_AT, roundTrip.getUsers()[0].assignmentAudit().lastModifiedTime());
  }

  @Test
  void testRoleGroupAssignmentResponseRoundTrip() throws Exception {
    GroupDTO group =
        GroupDTO.builder()
            .withId(2L)
            .withName("analysts")
            .withRoles(Collections.emptyList())
            .withAudit(buildEntityAudit())
            .build();
    RoleGroupAssignmentListResponse response =
        new RoleGroupAssignmentListResponse(
            new RoleGroupAssignmentDTO[] {
              new RoleGroupAssignmentDTO(group, buildAssignmentAudit(), 12)
            });

    String json = JsonUtils.objectMapper().writeValueAsString(response);
    RoleGroupAssignmentListResponse roundTrip =
        JsonUtils.objectMapper().readValue(json, RoleGroupAssignmentListResponse.class);
    roundTrip.validate();

    assertEquals("analysts", roundTrip.getGroups()[0].name());
    assertEquals(12, roundTrip.getGroups()[0].userCount());
    assertEquals("assigner", roundTrip.getGroups()[0].assignmentAudit().lastModifier());
  }

  private AuditDTO buildEntityAudit() {
    return AuditDTO.builder().withCreator("creator").withCreateTime(Instant.EPOCH).build();
  }

  private AuditDTO buildAssignmentAudit() {
    return AuditDTO.builder()
        .withCreator("creator")
        .withCreateTime(Instant.EPOCH)
        .withLastModifier("assigner")
        .withLastModifiedTime(ASSIGNED_AT)
        .build();
  }
}
