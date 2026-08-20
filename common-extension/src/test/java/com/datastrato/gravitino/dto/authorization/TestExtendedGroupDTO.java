/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Collections;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.GroupDTO;
import org.junit.jupiter.api.Test;

public class TestExtendedGroupDTO {

  @Test
  public void testFromDerivesIdentitySource() {
    ExtendedGroupDTO local = ExtendedGroupDTO.from(group("contractors", null));
    assertEquals("contractors", local.name());
    assertNull(local.externalId());
    assertEquals(IdentitySource.LOCAL, local.origin());

    ExtendedGroupDTO provisioned = ExtendedGroupDTO.from(group("governance", "azure-oid"));
    assertEquals("azure-oid", provisioned.externalId());
    assertEquals(IdentitySource.PROVISIONED, provisioned.origin());
  }

  private static Group group(String name, String externalId) {
    return GroupDTO.builder()
        .withId(1L)
        .withName(name)
        .withExternalId(externalId)
        .withRoles(Collections.emptyList())
        .withAudit(AuditDTO.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }
}
