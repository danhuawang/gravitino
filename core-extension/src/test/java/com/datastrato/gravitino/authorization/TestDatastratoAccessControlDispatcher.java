/*
 * Copyright 2025 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datastrato.gravitino.storage.InMemoryEntityStore;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AccessControlManager;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.idp.IdpUserGroupManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.service.DatastratoRoleMetaService;
import org.apache.gravitino.storage.relational.service.DatastratoUserMetaService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class TestDatastratoAccessControlDispatcher {

  private static DatastratoAccessControlDispatcher accessControlManager;

  private static EntityStore entityStore;
  private static CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
  private static AuthorizationPlugin authorizationPlugin;

  private static Config config;

  private static String METALAKE = "metalake";
  private static String CATALOG = "catalog";
  private static String SCHEMA = "schema";

  private static String USER = "user";

  private static String GROUP = "group";

  private static AuditInfo auditInfo =
      AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();

  private static BaseMetalake metalakeEntity =
      BaseMetalake.builder()
          .withId(1L)
          .withName(METALAKE)
          .withAuditInfo(auditInfo)
          .withVersion(SchemaVersion.V_0_1)
          .build();

  private static UserEntity userEntity =
      UserEntity.builder()
          .withNamespace(
              Namespace.of(METALAKE, Entity.SYSTEM_CATALOG_RESERVED_NAME, Entity.USER_SCHEMA_NAME))
          .withId(1L)
          .withName(USER)
          .withAuditInfo(auditInfo)
          .build();

  private static GroupEntity groupEntity =
      GroupEntity.builder()
          .withNamespace(
              Namespace.of(METALAKE, Entity.SYSTEM_CATALOG_RESERVED_NAME, Entity.GROUP_SCHEMA_NAME))
          .withId(1L)
          .withName(GROUP)
          .withAuditInfo(auditInfo)
          .build();

  private static RoleEntity roleEntity =
      RoleEntity.builder()
          .withNamespace(
              Namespace.of(METALAKE, Entity.SYSTEM_CATALOG_RESERVED_NAME, Entity.ROLE_SCHEMA_NAME))
          .withId(1L)
          .withName("role")
          .withProperties(Maps.newHashMap())
          .withSecurableObjects(
              Lists.newArrayList(
                  SecurableObjects.ofCatalog(
                      CATALOG, Lists.newArrayList(Privileges.UseCatalog.allow()))))
          .withAuditInfo(auditInfo)
          .build();

  @BeforeAll
  public static void setUp() throws Exception {
    config = new Config(false) {};
    config.set(Configs.SERVICE_ADMINS, Lists.newArrayList("admin"));
    config.set(Configs.TREE_LOCK_CLEAN_INTERVAL, 36000L);
    config.set(Configs.TREE_LOCK_MAX_NODE_IN_MEMORY, 100000L);
    config.set(Configs.TREE_LOCK_MIN_NODE_IN_MEMORY, 1000L);

    entityStore = new InMemoryEntityStore();
    entityStore.initialize(config);

    entityStore.put(metalakeEntity, true);
    entityStore.put(userEntity, true);
    entityStore.put(groupEntity, true);
    entityStore.put(roleEntity, true);

    accessControlManager =
        new DatastratoAccessControlDispatcher(
            new AccessControlManager(entityStore, new RandomIdGenerator(), config),
            entityStore,
            Mockito.mock(IdpUserGroupManager.class));

    FieldUtils.writeField(GravitinoEnv.getInstance(), "entityStore", entityStore, true);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "accessControlDispatcher", accessControlManager, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", catalogManager, true);
    BaseCatalog catalog = Mockito.mock(BaseCatalog.class);
    Mockito.when(catalogManager.loadCatalog(any())).thenReturn(catalog);
    Mockito.when(catalogManager.listCatalogs(Mockito.any()))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of("metalake", "catalog")});
    authorizationPlugin = Mockito.mock(AuthorizationPlugin.class);
    Mockito.when(catalog.getAuthorizationPlugin()).thenReturn(authorizationPlugin);

    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @AfterAll
  public static void tearDown() throws IOException {
    if (entityStore != null) {
      entityStore.close();
      entityStore = null;
    }
  }

  @Test
  public void testUpdatePrivileges() throws Exception {
    String testRole = "role";
    SecurableObject catalog =
        SecurableObjects.ofCatalog(
            CATALOG,
            Lists.newArrayList(Privileges.UseCatalog.allow(), Privileges.CreateTable.allow()));

    SecurableObject schema =
        SecurableObjects.ofSchema(
            catalog, SCHEMA, Lists.newArrayList(Privileges.CreateTable.allow()));

    // Add two securable objects
    Role role =
        accessControlManager.updatePrivilegesForRole(
            METALAKE, testRole, Lists.newArrayList(catalog, schema));

    List<SecurableObject> objects = role.securableObjects();

    Assertions.assertEquals(2, objects.size());

    // Remove one securable object
    role =
        accessControlManager.updatePrivilegesForRole(
            METALAKE, testRole, Lists.newArrayList(catalog));
    objects = role.securableObjects();
    Assertions.assertEquals(1, objects.size());
    Assertions.assertEquals(catalog, objects.get(0));

    // Update one securable object
    SecurableObject catalogAnother =
        SecurableObjects.ofCatalog(CATALOG, Lists.newArrayList(Privileges.UseCatalog.allow()));
    role =
        accessControlManager.updatePrivilegesForRole(
            METALAKE, testRole, Lists.newArrayList(catalogAnother));

    objects = role.securableObjects();
    Assertions.assertEquals(1, objects.size());
    Assertions.assertEquals(catalogAnother, objects.get(0));

    // Throw IllegalRoleException
    String notExist = "not-exist";
    Assertions.assertThrows(
        NoSuchRoleException.class,
        () ->
            accessControlManager.updatePrivilegesForRole(METALAKE, notExist, Lists.newArrayList()));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAssignRolesToPrincipalsWithDirectBatchService() {
    AccessControlDispatcher delegate = mock(AccessControlDispatcher.class);
    EntityStore batchEntityStore = mock(EntityStore.class);
    DatastratoRoleMetaService roleMetaService = mock(DatastratoRoleMetaService.class);
    DatastratoUserMetaService userMetaService = mock(DatastratoUserMetaService.class);
    DatastratoAccessControlDispatcher dispatcher =
        new DatastratoAccessControlDispatcher(
            delegate,
            batchEntityStore,
            mock(IdpUserGroupManager.class),
            roleMetaService,
            userMetaService);

    RoleEntity batchRole =
        RoleEntity.builder()
            .withNamespace(
                Namespace.of(
                    METALAKE, Entity.SYSTEM_CATALOG_RESERVED_NAME, Entity.ROLE_SCHEMA_NAME))
            .withId(10L)
            .withName("batch-role")
            .withProperties(Maps.newHashMap())
            .withSecurableObjects(
                Lists.newArrayList(
                    SecurableObjects.ofCatalog(
                        CATALOG, Lists.newArrayList(Privileges.UseCatalog.allow()))))
            .withAuditInfo(auditInfo)
            .build();
    RoleEntity secondBatchRole =
        RoleEntity.builder()
            .withNamespace(batchRole.namespace())
            .withId(20L)
            .withName("second-batch-role")
            .withProperties(Maps.newHashMap())
            .withSecurableObjects(Lists.newArrayList())
            .withAuditInfo(auditInfo)
            .build();
    UserEntity alice =
        UserEntity.builder()
            .withNamespace(
                Namespace.of(
                    METALAKE, Entity.SYSTEM_CATALOG_RESERVED_NAME, Entity.USER_SCHEMA_NAME))
            .withId(11L)
            .withName("alice")
            .withRoleNames(Lists.newArrayList("legacy"))
            .withRoleIds(Lists.newArrayList(99L))
            .withAuditInfo(auditInfo)
            .build();
    UserEntity bob =
        UserEntity.builder()
            .withNamespace(alice.namespace())
            .withId(12L)
            .withName("bob")
            .withRoleNames(Lists.newArrayList())
            .withRoleIds(Lists.newArrayList())
            .withAuditInfo(auditInfo)
            .build();
    GroupEntity analysts =
        GroupEntity.builder()
            .withNamespace(
                Namespace.of(
                    METALAKE, Entity.SYSTEM_CATALOG_RESERVED_NAME, Entity.GROUP_SCHEMA_NAME))
            .withId(21L)
            .withName("analysts")
            .withRoleNames(Lists.newArrayList())
            .withRoleIds(Lists.newArrayList())
            .withAuditInfo(auditInfo)
            .build();
    GroupEntity admins =
        GroupEntity.builder()
            .withNamespace(analysts.namespace())
            .withId(22L)
            .withName("admins")
            .withRoleNames(Lists.newArrayList())
            .withRoleIds(Lists.newArrayList())
            .withAuditInfo(auditInfo)
            .build();

    Mockito.when(delegate.getRole(METALAKE, "batch-role")).thenReturn(batchRole);
    Mockito.when(delegate.getRole(METALAKE, "second-batch-role")).thenReturn(secondBatchRole);
    Mockito.when(userMetaService.batchGetUsers(eq(METALAKE), anyList()))
        .thenReturn(Lists.newArrayList(bob, alice));
    Mockito.when(
            batchEntityStore.batchGet(
                Mockito.<NameIdentifier>anyList(),
                eq(Entity.EntityType.GROUP),
                eq(GroupEntity.class)))
        .thenReturn(Lists.newArrayList(admins, analysts));

    dispatcher.assignRolesToPrincipals(
        METALAKE,
        Lists.newArrayList("batch-role", "second-batch-role"),
        Lists.newArrayList("alice", "bob"),
        Lists.newArrayList("analysts", "admins"));

    ArgumentCaptor<List<RoleEntity>> rolesCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<UserEntity>> usersCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<GroupEntity>> groupsCaptor = ArgumentCaptor.forClass(List.class);
    verify(roleMetaService)
        .batchAssignRolesToPrincipals(
            rolesCaptor.capture(), usersCaptor.capture(), groupsCaptor.capture());

    Assertions.assertEquals("alice", usersCaptor.getValue().get(0).name());
    Assertions.assertEquals("bob", usersCaptor.getValue().get(1).name());
    Assertions.assertEquals(
        Lists.newArrayList(99L, 10L, 20L), usersCaptor.getValue().get(0).roleIds());
    Assertions.assertEquals(Lists.newArrayList(batchRole, secondBatchRole), rolesCaptor.getValue());
    Assertions.assertEquals("test", usersCaptor.getValue().get(0).auditInfo().creator());
    Assertions.assertEquals("analysts", groupsCaptor.getValue().get(0).name());
    Assertions.assertEquals("admins", groupsCaptor.getValue().get(1).name());
    Assertions.assertEquals(Lists.newArrayList(10L, 20L), groupsCaptor.getValue().get(0).roleIds());
    verify(delegate, never()).grantRolesToUser(any(), anyList(), any());
    verify(delegate, never()).grantRolesToGroup(any(), anyList(), any());

    Mockito.reset(roleMetaService);
    Mockito.when(
            batchEntityStore.batchGet(
                Mockito.<NameIdentifier>anyList(),
                eq(Entity.EntityType.GROUP),
                eq(GroupEntity.class)))
        .thenReturn(Lists.newArrayList());
    Assertions.assertThrows(
        NoSuchGroupException.class,
        () ->
            dispatcher.assignRolesToPrincipals(
                METALAKE,
                Lists.newArrayList("batch-role", "second-batch-role"),
                Lists.newArrayList("alice"),
                Lists.newArrayList("missing")));
    verify(roleMetaService, never()).batchAssignRolesToPrincipals(anyList(), anyList(), anyList());
  }

  @Test
  public void testExtendedSecurityBlankMetalakeValidation() {
    DatastratoAccessControlDispatcher dispatcher =
        new DatastratoAccessControlDispatcher(
            mock(AccessControlDispatcher.class),
            mock(EntityStore.class),
            mock(IdpUserGroupManager.class));

    Assertions.assertThrows(IllegalArgumentException.class, () -> dispatcher.listIdpUsers(" "));
    Assertions.assertThrows(IllegalArgumentException.class, () -> dispatcher.listIdpGroups(""));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.getExtendedUser(" ", "alice"));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.listExtendedGroupsForUser("", "alice"));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.listExtendedUsersForGroup(" ", "g"));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.listGroupsForUser(" ", "alice"));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.listUsersForGroup("", "g"));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.listExtendedUsers(" "));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.listExtendedGroups(""));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.countUsersByEnabled(""));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> dispatcher.countGroupsWithEmpty(" "));
  }
}
