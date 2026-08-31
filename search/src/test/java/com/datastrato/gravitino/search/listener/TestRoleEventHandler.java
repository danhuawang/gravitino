/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.listener.api.event.CreateRoleEvent;
import org.apache.gravitino.listener.api.event.DeleteRoleEvent;
import org.apache.gravitino.listener.api.event.GrantPrivilegesEvent;
import org.apache.gravitino.listener.api.event.OverridePrivilegesEvent;
import org.apache.gravitino.listener.api.event.RevokePrivilegesEvent;
import org.apache.gravitino.listener.api.info.RoleInfo;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestRoleEventHandler {
  private static final String METALAKE = "test";
  private static final String ROLE_NAME = "reader";

  private SearchService searchService;
  private RoleEventHandler handler;
  private MetadataObject table;
  private SecurableObject tableGrant;
  private RoleInfo roleInfo;
  private Privilege selectTable;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new RoleEventHandler(searchService);
    selectTable = Privileges.SelectTable.allow();
    table = MetadataObjects.parse("catalog.schema.table", MetadataObject.Type.TABLE);
    tableGrant =
        SecurableObjects.parse(table.fullName(), table.type(), ImmutableList.of(selectTable));
    roleInfo = new RoleInfo(ROLE_NAME, ImmutableMap.of(), ImmutableList.of(tableGrant));
  }

  @Test
  void testCreateRoleSynchronizesRoleAndAffectedData() {
    handler.handleEvent(new CreateRoleEvent("tester", METALAKE, roleInfo));

    verifyRoleSync();
    Mockito.verify(searchService).synchronizeMetadata(METALAKE, tableGrant, true);
  }

  @Test
  void testGrantAndRevokeSynchronizeRoleAndAffectedData() {
    handler.handleEvent(
        new GrantPrivilegesEvent(
            "tester", METALAKE, roleInfo, ImmutableSet.of(selectTable), table));
    handler.handleEvent(
        new RevokePrivilegesEvent(
            "tester", METALAKE, roleInfo, table, ImmutableSet.of(selectTable)));

    Mockito.verify(searchService, Mockito.times(2))
        .synchronizeMetadata(
            NameIdentifierUtil.ofRole(METALAKE, ROLE_NAME), EntityType.ROLE, false);
    Mockito.verify(searchService, Mockito.times(2)).synchronizeMetadata(METALAKE, table, true);
  }

  @Test
  void testOverrideReconcilesMetalakeToRemoveOldScopes() {
    handler.handleEvent(
        new OverridePrivilegesEvent("tester", METALAKE, roleInfo, ImmutableList.of(tableGrant)));

    verifyRoleSync();
    verifyMetalakeReconciliation();
  }

  @Test
  void testDeleteRemovesRoleAndReconcilesPermissionDocuments() {
    handler.handleEvent(new DeleteRoleEvent("tester", METALAKE, ROLE_NAME, true));

    Mockito.verify(searchService).removeEntityByName(METALAKE, ROLE_NAME, EntityType.ROLE);
    verifyMetalakeReconciliation();
  }

  @Test
  void testDeletingMissingRoleIsIgnored() {
    handler.handleEvent(new DeleteRoleEvent("tester", METALAKE, ROLE_NAME, false));

    Mockito.verifyNoInteractions(searchService);
  }

  private void verifyRoleSync() {
    Mockito.verify(searchService)
        .synchronizeMetadata(
            NameIdentifierUtil.ofRole(METALAKE, ROLE_NAME), EntityType.ROLE, false);
  }

  private void verifyMetalakeReconciliation() {
    Mockito.verify(searchService)
        .synchronizeMetadata(NameIdentifier.of(METALAKE), EntityType.METALAKE, true);
  }
}
