/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import java.util.List;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.junit.jupiter.api.Test;

class TestScimResourceConverter {

  @Test
  void testToUser() {
    var user = ScimServiceTestEntities.user(1L, "alice", "ext-alice", false);
    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("1", scimUser.getId());
    assertEquals("ext-alice", scimUser.getExternalId());
    assertEquals("alice", scimUser.getUserName());
    assertEquals(Boolean.FALSE, scimUser.getActive());
    assertTrue(scimUser.getSchemas().contains(ScimUser.SCHEMA_URI));
  }

  @Test
  void testToUserWithoutExternalId() {
    var user = ScimServiceTestEntities.user(1L, "alice", null, true);
    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("1", scimUser.getId());
    assertNull(scimUser.getExternalId());
    assertEquals("alice", scimUser.getUserName());
  }

  @Test
  void testToGroup() {
    var group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    ScimGroup scimGroup = ScimResourceConverter.toScimGroup(group, List.of("100"));
    assertEquals("1", scimGroup.getId());
    assertEquals("ext-g1", scimGroup.getExternalId());
    assertEquals("engineers", scimGroup.getDisplayName());
    assertEquals(1, scimGroup.getMembers().size());
    assertEquals("100", scimGroup.getMembers().get(0).getValue());
    assertTrue(scimGroup.getSchemas().contains(ScimGroup.SCHEMA_URI));
  }

  @Test
  void testToGroupWithoutExternalId() {
    var group = ScimServiceTestEntities.group(1L, "engineers", null);
    ScimGroup scimGroup = ScimResourceConverter.toScimGroup(group, List.of());
    assertEquals("1", scimGroup.getId());
    assertNull(scimGroup.getExternalId());
    assertEquals("engineers", scimGroup.getDisplayName());
    assertTrue(scimGroup.getMembers().isEmpty());
  }
}
