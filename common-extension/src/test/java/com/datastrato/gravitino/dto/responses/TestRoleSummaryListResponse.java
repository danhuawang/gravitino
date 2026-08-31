/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.dto.authorization.RoleMembershipDTO;
import com.datastrato.gravitino.dto.authorization.RoleSummaryDTO;
import java.time.Instant;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

class TestRoleSummaryListResponse {

  private static final Instant CREATED_AT = Instant.parse("2026-08-28T01:02:03Z");

  @Test
  void testRoleSummaryResponseRoundTrip() throws Exception {
    OwnerDTO owner = OwnerDTO.builder().withName("alice").withType(Owner.Type.USER).build();
    RoleSummaryListResponse response =
        new RoleSummaryListResponse(
            new RoleSummaryDTO[] {
              new RoleSummaryDTO("admin", owner, CREATED_AT, 2, 1),
              new RoleSummaryDTO("viewer", null, CREATED_AT, 0, 0)
            });

    String json = JsonUtils.objectMapper().writeValueAsString(response);
    RoleSummaryListResponse roundTrip =
        JsonUtils.objectMapper().readValue(json, RoleSummaryListResponse.class);
    roundTrip.validate();

    assertEquals("admin", roundTrip.getRoles()[0].getRole());
    assertEquals("alice", roundTrip.getRoles()[0].getOwner().name());
    assertEquals(Owner.Type.USER, roundTrip.getRoles()[0].getOwner().type());
    assertEquals(2, roundTrip.getRoles()[0].getUserCount());
    assertEquals(1, roundTrip.getRoles()[0].getGroupCount());
    assertEquals(3, roundTrip.getRoles()[0].getAssignCount());
    assertEquals(CREATED_AT, roundTrip.getRoles()[0].getCreateTime());
    assertNull(roundTrip.getRoles()[1].getOwner());
  }

  @Test
  void testRoleMembershipDoesNotContainOwner() throws Exception {
    RoleMembershipDTO membership =
        new RoleMembershipDTO(
            "admin",
            new String[] {"alice"},
            new String[] {"analysts"},
            new String[] {"catalog1"},
            1,
            2);

    assertFalse(
        JsonUtils.objectMapper()
            .readTree(JsonUtils.objectMapper().writeValueAsString(membership))
            .has("owner"));
  }

  @Test
  void testRoleSummaryRejectsNegativeCounts() {
    assertThrows(
        IllegalArgumentException.class, () -> new RoleSummaryDTO("admin", null, CREATED_AT, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RoleSummaryDTO("admin", null, CREATED_AT, 0, -1));
    assertThrows(
        IllegalArgumentException.class, () -> new RoleSummaryDTO("admin", null, null, 0, 0));
  }
}
