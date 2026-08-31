/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.service.ScimServiceTestEntities;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestScimResourceConverter {

  @AfterEach
  void tearDown() {
    ScimMetalakeContext.clear();
  }

  @Test
  void testToUser() {
    Instant created = Instant.parse("2024-01-02T03:04:05Z");
    Instant modified = Instant.parse("2024-02-03T04:05:06Z");
    var user = ScimServiceTestEntities.user(1L, "alice", "ext-alice", false, created, modified);
    ScimMetalakeContext.setMetalake("test");
    ScimMetalakeContext.setRequestBaseUri("http://localhost:9201");

    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("1", scimUser.getId());
    assertEquals("ext-alice", scimUser.getExternalId());
    assertEquals("alice", scimUser.getUserName());
    assertEquals(Boolean.FALSE, scimUser.getActive());
    assertTrue(scimUser.getSchemas().contains(ScimUser.SCHEMA_URI));
    assertNotNull(scimUser.getMeta());
    assertEquals("User", scimUser.getMeta().getResourceType());
    assertEquals(LocalDateTime.ofInstant(created, ZoneOffset.UTC), scimUser.getMeta().getCreated());
    assertEquals(
        LocalDateTime.ofInstant(modified, ZoneOffset.UTC), scimUser.getMeta().getLastModified());
    assertEquals(
        "http://localhost:9201/scim/v2/metalakes/test/Users/1", scimUser.getMeta().getLocation());
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
    assertNull(scimUser.getMeta().getLocation());
  }

  @Test
  void testToGroup() {
    Instant created = Instant.parse("2024-01-02T03:04:05Z");
    var group = ScimServiceTestEntities.group(1L, "engineers", "ext-g1", created, created);
    ScimMetalakeContext.setMetalake("test");
    ScimMetalakeContext.setRequestBaseUri("http://localhost:9201");

    ScimGroup scimGroup = ScimResourceConverter.toScimGroup(group, List.of("100"));
    assertEquals("1", scimGroup.getId());
    assertEquals("ext-g1", scimGroup.getExternalId());
    assertEquals("engineers", scimGroup.getDisplayName());
    assertEquals(1, scimGroup.getMembers().size());
    assertEquals("100", scimGroup.getMembers().get(0).getValue());
    assertTrue(scimGroup.getSchemas().contains(ScimGroup.SCHEMA_URI));
    assertNotNull(scimGroup.getMeta());
    assertEquals("Group", scimGroup.getMeta().getResourceType());
    assertEquals(
        "http://localhost:9201/scim/v2/metalakes/test/Groups/1", scimGroup.getMeta().getLocation());
  }

  @Test
  void testToUserLocationFallsBackToRelativePathWithoutBaseUri() {
    var user = ScimServiceTestEntities.user(1L, "alice", null, true);
    ScimMetalakeContext.setMetalake("test");

    ScimUser scimUser = ScimResourceConverter.toScimUser(user);
    assertEquals("/scim/v2/metalakes/test/Users/1", scimUser.getMeta().getLocation());
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
