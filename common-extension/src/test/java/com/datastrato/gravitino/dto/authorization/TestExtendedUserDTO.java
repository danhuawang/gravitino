/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.UserDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

public class TestExtendedUserDTO {

  @Test
  public void testIdentitySourceFromExternalId() {
    assertEquals(IdentitySource.LOCAL, IdentitySource.fromExternalId(null));
    assertEquals(IdentitySource.LOCAL, IdentitySource.fromExternalId(""));
    assertEquals(IdentitySource.LOCAL, IdentitySource.fromExternalId("   "));
    assertEquals(IdentitySource.PROVISIONED, IdentitySource.fromExternalId("ext-1"));
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
    ExtendedUserDTO local = ExtendedUserDTO.from(user("lee.p", null));
    assertEquals("lee.p", local.name());
    assertNull(local.externalId());
    assertEquals(IdentitySource.LOCAL, local.origin());
    assertTrue(JsonUtils.objectMapper().writeValueAsString(local).contains("\"origin\":\"Local\""));

    ExtendedUserDTO provisioned = ExtendedUserDTO.from(user("dana.k", "azure-oid"));
    assertEquals("azure-oid", provisioned.externalId());
    assertEquals(IdentitySource.PROVISIONED, provisioned.origin());
  }

  private static User user(String name, String externalId) {
    return UserDTO.builder()
        .withId(1L)
        .withName(name)
        .withExternalId(externalId)
        .withEnabled(true)
        .withRoles(Collections.emptyList())
        .withAudit(AuditDTO.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
