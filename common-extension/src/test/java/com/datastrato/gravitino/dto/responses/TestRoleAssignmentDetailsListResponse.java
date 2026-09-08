/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datastrato.gravitino.dto.authorization.AssignedRoleDTO;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDetailsDTO;
import com.datastrato.gravitino.dto.authorization.RoleMembersDTO;
import java.time.Instant;
import java.util.Map;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

class TestRoleAssignmentDetailsListResponse {

  private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T01:02:03Z");

  @Test
  void testRoundTripWithoutPrivileges() throws Exception {
    AuditDTO roleAudit =
        AuditDTO.builder().withCreator("role-creator").withCreateTime(Instant.EPOCH).build();
    AuditDTO assignmentAudit =
        AuditDTO.builder().withCreator("assigner").withCreateTime(ASSIGNED_AT).build();
    RoleAssignmentDetailsListResponse response =
        new RoleAssignmentDetailsListResponse(
            new RoleAssignmentDetailsDTO[] {
              new RoleAssignmentDetailsDTO(
                  new AssignedRoleDTO("analyst", Map.of("scope", "reporting"), roleAudit),
                  new RoleMembersDTO(new String[] {"alice", "bob"}, new String[] {"analytics"}),
                  OwnerDTO.builder().withName("owner1").withType(Owner.Type.USER).build(),
                  assignmentAudit)
            });

    String json = JsonUtils.objectMapper().writeValueAsString(response);
    assertFalse(json.contains("securableObjects"));
    assertFalse(json.contains("privilege"));
    assertFalse(json.contains("assignmentAudit"));

    RoleAssignmentDetailsListResponse roundTrip =
        JsonUtils.objectMapper().readValue(json, RoleAssignmentDetailsListResponse.class);
    roundTrip.validate();

    RoleAssignmentDetailsDTO assignment = roundTrip.getRoles()[0];
    assertEquals("analyst", assignment.getRole().getName());
    assertEquals("reporting", assignment.getRole().getProperties().get("scope"));
    assertArrayEquals(new String[] {"alice", "bob"}, assignment.getMembers().getUsers());
    assertArrayEquals(new String[] {"analytics"}, assignment.getMembers().getGroups());
    assertEquals("owner1", assignment.getOwner().name());
    assertEquals(Owner.Type.USER, assignment.getOwner().type());
    assertEquals("assigner", assignment.getAssignment().creator());
    assertEquals(ASSIGNED_AT, assignment.getAssignment().createTime());
  }
}
