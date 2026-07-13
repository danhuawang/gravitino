/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.junit.jupiter.api.Test;

class TestScimResourceConverter {

  @Test
  void testToUser() {
    var user = ScimServiceTestEntities.user(1L, "alice", "ext-alice", false);
    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("ext-alice", scimUser.getId());
    assertEquals("ext-alice", scimUser.getExternalId());
    assertEquals("alice", scimUser.getUserName());
    assertEquals(Boolean.FALSE, scimUser.getActive());
    assertTrue(scimUser.getSchemas().contains(ScimUser.SCHEMA_URI));
  }
}
