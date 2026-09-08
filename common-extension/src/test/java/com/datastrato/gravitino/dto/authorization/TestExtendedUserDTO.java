/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.UserDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

public class TestExtendedUserDTO {

  @Test
  public void testIdentitySourceFromIdpMembership() {
    assertEquals(IdentitySource.LOCAL, IdentitySource.fromIdpMembership(true));
    assertEquals(IdentitySource.PROVISIONED, IdentitySource.fromIdpMembership(false));
    assertEquals(IdentitySource.LOCAL, IdentitySource.fromValue("Local"));
    assertEquals(IdentitySource.PROVISIONED, IdentitySource.fromValue("Provisioned"));
    assertThrows(IllegalArgumentException.class, () -> IdentitySource.fromValue("unknown"));
  }

  @Test
  public void testIdentitySourceJsonValue() throws Exception {
    assertEquals("\"Local\"", JsonUtils.objectMapper().writeValueAsString(IdentitySource.LOCAL));
    assertEquals(
        IdentitySource.PROVISIONED,
        JsonUtils.objectMapper().readValue("\"Provisioned\"", IdentitySource.class));
  }

  @Test
  public void testFromDerivesIdentitySource() throws Exception {
    ExtendedUserDTO local = ExtendedUserDTO.from(user("lee.p"), true);
    assertEquals("lee.p", local.name());
    assertEquals(IdentitySource.LOCAL, local.origin());
    assertTrue(local.enabled());
    assertEquals(null, local.externalId());
    assertTrue(JsonUtils.objectMapper().writeValueAsString(local).contains("\"origin\":\"Local\""));
    assertTrue(JsonUtils.objectMapper().writeValueAsString(local).contains("\"enabled\":true"));
    assertFalse(JsonUtils.objectMapper().writeValueAsString(local).contains("externalId"));

    ExtendedUserDTO provisioned =
        ExtendedUserDTO.from(
            user("dana.k"),
            IdentitySource.PROVISIONED,
            Collections.emptyList(),
            true,
            "51d2e02d-7e1f-49e4-e7cb-7da2050dcb3b");
    assertEquals(IdentitySource.PROVISIONED, provisioned.origin());
    assertTrue(provisioned.enabled());
    assertEquals("51d2e02d-7e1f-49e4-e7cb-7da2050dcb3b", provisioned.externalId());
    assertTrue(
        JsonUtils.objectMapper()
            .writeValueAsString(provisioned)
            .contains("\"externalId\":\"51d2e02d-7e1f-49e4-e7cb-7da2050dcb3b\""));
  }

  @Test
  public void testFromJitDefaultsEnabledAndOmitsExternalId() throws Exception {
    ExtendedUserDTO jit =
        ExtendedUserDTO.from(
            user("guest.j"),
            IdentitySource.JIT,
            Collections.emptyList(),
            true,
            "should-be-omitted");

    assertEquals(IdentitySource.JIT, jit.origin());
    assertTrue(jit.enabled());
    assertEquals(null, jit.externalId());
    String json = JsonUtils.objectMapper().writeValueAsString(jit);
    assertTrue(json.contains("\"origin\":\"JIT\""));
    assertTrue(json.contains("\"enabled\":true"));
    assertFalse(json.contains("externalId"));
  }

  @Test
  public void testFromUsersWithGroups() {
    ExtendedUserDTO[] users =
        ExtendedUserDTO.from(
            List.of(
                userWithGroups("lee.p", List.of("contractors"), true),
                userWithGroups("dana.k", Collections.emptyList(), false)));

    assertEquals(2, users.length);
    assertEquals("lee.p", users[0].name());
    assertEquals(List.of("contractors"), users[0].groups());
    assertEquals(IdentitySource.LOCAL, users[0].origin());
    assertEquals("dana.k", users[1].name());
    assertEquals(Collections.emptyList(), users[1].groups());
    assertEquals(IdentitySource.PROVISIONED, users[1].origin());
  }

  private static ExtendedUserDTO.UserWithGroupNames userWithGroups(
      String name, List<String> groups, boolean inBuiltInIdp) {
    User user = user(name);
    IdentitySource origin = IdentitySource.fromIdpMembership(inBuiltInIdp);
    return new ExtendedUserDTO.UserWithGroupNames() {
      @Override
      public User user() {
        return user;
      }

      @Override
      public List<String> groups() {
        return groups;
      }

      @Override
      public IdentitySource origin() {
        return origin;
      }
    };
  }

  private static User user(String name) {
    return UserDTO.builder()
        .withId(1L)
        .withName(name)
        .withRoles(Collections.emptyList())
        .withAudit(AuditDTO.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
