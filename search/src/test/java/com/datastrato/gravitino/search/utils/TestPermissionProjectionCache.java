/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.search.utils.PermissionProjectionCache.Permissions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.utils.NamespaceUtil;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestPermissionProjectionCache {

  @Test
  void testProjectsAncestorRoleAndDirectUserPermissions() {
    DatastratoAccessControlDispatcher dispatcher =
        Mockito.mock(DatastratoAccessControlDispatcher.class);
    SecurableObject catalog = SecurableObjects.ofCatalog("catalog", ImmutableList.of());
    SecurableObject schema = SecurableObjects.ofSchema(catalog, "schema", ImmutableList.of());
    SecurableObject tableGrant =
        SecurableObjects.ofSchema(
            catalog, "schema", ImmutableList.of(Privileges.SelectTable.allow()));
    RoleEntity role = newRole("reader", tableGrant);
    UserEntity user = newUser("alice", "reader");
    Mockito.when(dispatcher.listRoleNames("test")).thenReturn(new String[] {"reader"});
    Mockito.when(dispatcher.getRole("test", "reader")).thenReturn(role);
    Mockito.when(dispatcher.listUsersByRole("test", "reader")).thenReturn(new UserEntity[] {user});

    Permissions permissions =
        PermissionProjectionCache.getPermissions(
            "test",
            MetadataObjects.parse("catalog.schema.table", MetadataObject.Type.TABLE),
            dispatcher);

    assertEquals(1, permissions.rolePermissions().size());
    assertEquals(1, permissions.userPermissions().size());
    String roleJson = SearchEntityCodec.INSTANCE.serialize(permissions.rolePermissions().get(0));
    String userJson = SearchEntityCodec.INSTANCE.serialize(permissions.userPermissions().get(0));
    assertTrue(roleJson.contains("\"name\":\"reader\""));
    assertTrue(roleJson.contains("\"permission\":\"ALLOW select table\""));
    assertTrue(userJson.contains("\"name\":\"alice\""));

    Permissions schemaPermissions =
        PermissionProjectionCache.getPermissions(
            "test",
            MetadataObjects.parse(schema.fullName(), MetadataObject.Type.SCHEMA),
            dispatcher);
    assertEquals(1, schemaPermissions.rolePermissions().size());
    assertEquals(1, schemaPermissions.userPermissions().size());

    Permissions siblingPermissions =
        PermissionProjectionCache.getPermissions(
            "test",
            MetadataObjects.parse("catalog.other.table", MetadataObject.Type.TABLE),
            dispatcher);
    assertTrue(siblingPermissions.rolePermissions().isEmpty());
    assertTrue(siblingPermissions.userPermissions().isEmpty());
  }

  @Test
  void testDoesNotExpandGroupAssignmentsIntoUsers() {
    DatastratoAccessControlDispatcher dispatcher =
        Mockito.mock(DatastratoAccessControlDispatcher.class);
    SecurableObject table =
        SecurableObjects.parse(
            "catalog.schema.table",
            MetadataObject.Type.TABLE,
            ImmutableList.of(Privileges.SelectTable.allow()));
    Mockito.when(dispatcher.listRoleNames("test")).thenReturn(new String[] {"group_reader"});
    Mockito.when(dispatcher.getRole("test", "group_reader"))
        .thenReturn(newRole("group_reader", table));
    Mockito.when(dispatcher.listUsersByRole("test", "group_reader")).thenReturn(new UserEntity[0]);

    Permissions permissions =
        PermissionProjectionCache.getPermissions(
            "test", MetadataObjects.parse(table.fullName(), MetadataObject.Type.TABLE), dispatcher);

    assertEquals(1, permissions.rolePermissions().size());
    assertTrue(permissions.userPermissions().isEmpty());
  }

  @Test
  void testMetalakeGrantAppliesToDescendantsAndDeduplicates() {
    DatastratoAccessControlDispatcher dispatcher =
        Mockito.mock(DatastratoAccessControlDispatcher.class);
    SecurableObject metalake =
        SecurableObjects.ofMetalake(
            "test",
            ImmutableList.of(Privileges.SelectTable.allow(), Privileges.SelectTable.allow()));
    Mockito.when(dispatcher.listRoleNames("test")).thenReturn(new String[] {"admin"});
    Mockito.when(dispatcher.getRole("test", "admin")).thenReturn(newRole("admin", metalake));
    Mockito.when(dispatcher.listUsersByRole("test", "admin")).thenReturn(new UserEntity[0]);

    Permissions permissions =
        PermissionProjectionCache.getPermissions(
            "test",
            MetadataObjects.parse("catalog.schema.table", MetadataObject.Type.TABLE),
            dispatcher);

    assertEquals(1, permissions.rolePermissions().size());
  }

  private static RoleEntity newRole(String name, SecurableObject securableObject) {
    return RoleEntity.builder()
        .withId(10L)
        .withName(name)
        .withProperties(ImmutableMap.of())
        .withAuditInfo(AuditInfo.EMPTY)
        .withNamespace(NamespaceUtil.ofRole("test"))
        .withSecurableObjects(ImmutableList.of(securableObject))
        .build();
  }

  private static UserEntity newUser(String name, String role) {
    return UserEntity.builder()
        .withId(20L)
        .withName(name)
        .withAuditInfo(AuditInfo.EMPTY)
        .withNamespace(NamespaceUtil.ofUser("test"))
        .withRoleNames(ImmutableList.of(role))
        .withRoleIds(ImmutableList.of(10L))
        .build();
  }
}
