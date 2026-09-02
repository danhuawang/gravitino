/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.web.ScimRequestContext;
import java.util.List;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestScimResourceConverter {

  @AfterEach
  void tearDown() {
    ScimRequestContext.clear();
  }

  @Test
  void testToUser() {
    var user = ScimServiceTestEntities.user(1L, "alice", "ext-alice", false);
    ScimRequestContext.bindRequestBaseUri("http://localhost:9201");

    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("1", scimUser.getId());
    assertEquals("ext-alice", scimUser.getExternalId());
    assertEquals("alice", scimUser.getUserName());
    assertEquals(Boolean.FALSE, scimUser.getActive());
    assertTrue(scimUser.getSchemas().contains(ScimUser.SCHEMA_URI));
    assertNotNull(scimUser.getMeta());
    assertEquals("User", scimUser.getMeta().getResourceType());
    assertNotNull(scimUser.getMeta().getCreated());
    assertNotNull(scimUser.getMeta().getLastModified());
    assertEquals("http://localhost:9201/scim/v2/Users/1", scimUser.getMeta().getLocation());
  }

  @Test
  void testToUserWithoutExternalId() {
    var user = ScimServiceTestEntities.user(1L, "alice", null, true);
    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("1", scimUser.getId());
    assertNull(scimUser.getExternalId());
    assertEquals("alice", scimUser.getUserName());
    assertNotNull(scimUser.getMeta());
    assertEquals("User", scimUser.getMeta().getResourceType());
    assertEquals("/scim/v2/Users/1", scimUser.getMeta().getLocation());
  }

  @Test
  void testToGroup() {
    var group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1");
    ScimRequestContext.bindRequestBaseUri("http://localhost:9201");

    ScimGroup scimGroup = ScimResourceConverter.toScimGroup(group, List.of("100"));
    assertEquals("1", scimGroup.getId());
    assertEquals("ext-g1", scimGroup.getExternalId());
    assertEquals("engineers", scimGroup.getDisplayName());
    assertEquals(1, scimGroup.getMembers().size());
    assertEquals("100", scimGroup.getMembers().get(0).getValue());
    assertTrue(scimGroup.getSchemas().contains(ScimGroup.SCHEMA_URI));
    assertNotNull(scimGroup.getMeta());
    assertEquals("Group", scimGroup.getMeta().getResourceType());
    assertEquals("http://localhost:9201/scim/v2/Groups/1", scimGroup.getMeta().getLocation());
  }

  @Test
  void testToUserLocationFallsBackToRelativePathWithoutBaseUri() {
    var user = ScimServiceTestEntities.user(1L, "alice", "ext-alice", true);

    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("/scim/v2/Users/1", scimUser.getMeta().getLocation());
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
