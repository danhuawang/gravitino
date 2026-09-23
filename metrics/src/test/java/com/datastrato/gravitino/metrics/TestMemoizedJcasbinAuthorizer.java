/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.casbin.jcasbin.main.Enforcer;
import org.junit.jupiter.api.Test;

class TestMemoizedJcasbinAuthorizer {

  @Test
  void testPreloadLoadsRelationsAndUserOwnershipOnce() {
    String metalake = "metalake";
    long userId = 1L;
    long roleId = 10L;
    long assetId = 101L;
    MetadataObject catalog = MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);
    Owner owner = mock(Owner.class);
    when(owner.name()).thenReturn("user");
    when(owner.type()).thenReturn(Owner.Type.USER);
    AssetNode node = assetNode(assetId);
    when(node.getOwners()).thenReturn(ImmutableSet.of(owner));
    NameIdentifier ident = MetadataObjectUtil.toEntityIdent(metalake, catalog);

    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getAssetNodeByIdent()).thenReturn(ImmutableMap.of(ident, node));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(assetId, node));
    when(snapshot.getUserNameToUserId()).thenReturn(ImmutableMap.of("user", userId));
    when(snapshot.getUserIdToRoleIds())
        .thenReturn(ImmutableMap.of(userId, ImmutableSet.of(roleId)));
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(roleId, Collections.emptyList()));

    Enforcer allowEnforcer = mock(Enforcer.class);
    Enforcer denyEnforcer = mock(Enforcer.class);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, denyEnforcer, ImmutableMap.of(metalake, snapshot));

    authorizer.preloadSnapshotPolicies(metalake);
    authorizer.preloadSnapshotPolicies(metalake);

    verify(allowEnforcer, times(1)).addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
    verify(denyEnforcer, times(1)).addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
    verify(allowEnforcer, times(1)).addPolicy(anyList());

    clearInvocations(allowEnforcer, denyEnforcer);
    authorizer.authorize(
        new UserPrincipal("user"),
        metalake,
        catalog,
        Privilege.Name.USE_CATALOG,
        new AuthorizationRequestContext());

    verify(allowEnforcer, never()).addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
    verify(denyEnforcer, never()).addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
    verify(allowEnforcer, never()).addPolicy(anyList());
  }

  @Test
  void testMissingOwnerUserDoesNotBlockHealthyOwnershipOrPreloadCompletion() {
    String metalake = "metalake";
    Owner missingOwner = mock(Owner.class);
    when(missingOwner.name()).thenReturn("deleted_user");
    when(missingOwner.type()).thenReturn(Owner.Type.USER);
    Owner healthyOwner = mock(Owner.class);
    when(healthyOwner.name()).thenReturn("user");
    when(healthyOwner.type()).thenReturn(Owner.Type.USER);
    AssetNode node = assetNode(101L);
    when(node.getOwners()).thenReturn(ImmutableSet.of(missingOwner, healthyOwner));
    MetadataObject catalog = MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);
    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getUserNameToUserId()).thenReturn(ImmutableMap.of("user", 1L));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(101L, node));
    when(snapshot.getAssetNodeByIdent())
        .thenReturn(ImmutableMap.of(MetadataObjectUtil.toEntityIdent(metalake, catalog), node));
    Enforcer allowEnforcer = mock(Enforcer.class);
    Enforcer denyEnforcer = mock(Enforcer.class);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, denyEnforcer, ImmutableMap.of(metalake, snapshot));

    authorizer.preloadSnapshotPolicies(metalake);

    verify(allowEnforcer).addPolicy(ImmutableList.of("1", "CATALOG", "101", "OWNER", "allow"));
    clearInvocations(allowEnforcer, denyEnforcer, node);
    authorizer.preloadSnapshotPolicies(metalake);
    authorizer.authorize(
        new UserPrincipal("user"),
        metalake,
        catalog,
        Privilege.Name.USE_CATALOG,
        new AuthorizationRequestContext());
    verify(node, never()).getOwners();
    verify(allowEnforcer, never()).addPolicy(anyList());
  }

  @Test
  void testPreloadWarningDoesNotSuppressOnDemandFailureWarnings() {
    String metalake = "metalake";
    AssetNode node = assetNode(101L);
    Owner owner = mock(Owner.class);
    when(owner.type()).thenReturn(Owner.Type.USER);
    when(owner.name()).thenReturn("user");
    when(node.getOwners()).thenReturn(ImmutableSet.of(owner));
    MetadataObject catalog = MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);
    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getUserNameToUserId()).thenReturn(ImmutableMap.of("user", 1L));
    when(snapshot.getUserIdToRoleIds()).thenReturn(ImmutableMap.of(1L, ImmutableSet.of(10L)));
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(10L, Collections.emptyList()));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(101L, node));
    when(snapshot.getAssetNodeByIdent())
        .thenReturn(ImmutableMap.of(MetadataObjectUtil.toEntityIdent(metalake, catalog), node));
    Enforcer allowEnforcer = mock(Enforcer.class);
    when(allowEnforcer.addRoleForUser("1", "10"))
        .thenThrow(new IllegalStateException("role load failed"));
    when(allowEnforcer.addPolicy(anyList()))
        .thenThrow(new IllegalStateException("owner load failed"));
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, mock(Enforcer.class), ImmutableMap.of(metalake, snapshot));
    LoggerContext context =
        (LoggerContext)
            LogManager.getContext(MemoizedJcasbinAuthorizer.class.getClassLoader(), false);
    Configuration configuration = context.getConfiguration();
    String loggerName = MemoizedJcasbinAuthorizer.class.getName();
    LoggerConfig previousConfig = configuration.getLoggers().get(loggerName);
    Appender appender = mock(Appender.class);
    when(appender.getName()).thenReturn("metricsAuthorizationWarnings");
    when(appender.isStarted()).thenReturn(true);
    List<String> warnings = new ArrayList<>();
    doAnswer(
            invocation -> {
              LogEvent event = invocation.getArgument(0);
              warnings.add(event.getMessage().getFormattedMessage());
              return null;
            })
        .when(appender)
        .append(any(LogEvent.class));
    LoggerConfig loggerConfig = new LoggerConfig(loggerName, Level.WARN, false);
    loggerConfig.addAppender(appender, Level.WARN, null);
    configuration.removeLogger(loggerName);
    configuration.addLogger(loggerName, loggerConfig);
    context.updateLoggers();
    try {
      authorizer.preloadSnapshotPolicies(metalake);
      for (int attempt = 0; attempt < 2; attempt++) {
        authorizer.authorize(
            new UserPrincipal("user"),
            metalake,
            catalog,
            Privilege.Name.USE_CATALOG,
            new AuthorizationRequestContext());
      }

      assertEquals(3, warnings.size());
      assertTrue(warnings.get(0).contains("preload incomplete"));
      assertTrue(warnings.get(1).contains("metalake=metalake, userId=1"));
      assertTrue(warnings.get(2).contains("metalake=metalake, objectId=101"));
    } finally {
      configuration.removeLogger(loggerName);
      if (previousConfig != null) {
        configuration.addLogger(loggerName, previousConfig);
      }
      context.updateLoggers();
    }
  }

  @Test
  void testUserRoleRelationIsLoadedOnceAcrossAssetAuthorizations() {
    String metalake = "metalake";
    long userId = 1L;
    long roleId = 10L;
    long firstAssetId = 101L;
    long secondAssetId = 102L;
    MetadataObject firstCatalog = MetadataObjects.of(null, "catalog1", MetadataObject.Type.CATALOG);
    MetadataObject secondCatalog =
        MetadataObjects.of(null, "catalog2", MetadataObject.Type.CATALOG);
    AssetNode firstNode = assetNode(firstAssetId);
    AssetNode secondNode = assetNode(secondAssetId);
    NameIdentifier firstIdent = MetadataObjectUtil.toEntityIdent(metalake, firstCatalog);
    NameIdentifier secondIdent = MetadataObjectUtil.toEntityIdent(metalake, secondCatalog);

    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getAssetNodeByIdent())
        .thenReturn(ImmutableMap.of(firstIdent, firstNode, secondIdent, secondNode));
    when(snapshot.getAssetNodeById())
        .thenReturn(ImmutableMap.of(firstAssetId, firstNode, secondAssetId, secondNode));
    when(snapshot.getUserNameToUserId()).thenReturn(ImmutableMap.of("user", userId));
    when(snapshot.getUserIdToRoleIds())
        .thenReturn(ImmutableMap.of(userId, ImmutableSet.of(roleId)));
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(roleId, Collections.emptyList()));

    Enforcer allowEnforcer = mock(Enforcer.class);
    Enforcer denyEnforcer = mock(Enforcer.class);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, denyEnforcer, ImmutableMap.of(metalake, snapshot));

    UserPrincipal principal = new UserPrincipal("user");
    AuthorizationRequestContext requestContext = new AuthorizationRequestContext();
    authorizer.authorize(
        principal, metalake, firstCatalog, Privilege.Name.USE_CATALOG, requestContext);
    authorizer.authorize(
        principal, metalake, secondCatalog, Privilege.Name.USE_CATALOG, requestContext);

    verify(allowEnforcer).addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
    verify(denyEnforcer).addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
  }

  @Test
  void testSharedRolePolicyIsLoadedOnceAcrossUsers() {
    String metalake = "metalake";
    long firstUserId = 1L;
    long secondUserId = 2L;
    long roleId = 10L;
    long assetId = 101L;
    MetadataObject catalog = MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);
    AssetNode node = assetNode(assetId);
    NameIdentifier ident = MetadataObjectUtil.toEntityIdent(metalake, catalog);

    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getAssetNodeByIdent()).thenReturn(ImmutableMap.of(ident, node));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(assetId, node));
    when(snapshot.getUserNameToUserId())
        .thenReturn(ImmutableMap.of("first", firstUserId, "second", secondUserId));
    when(snapshot.getUserIdToRoleIds())
        .thenReturn(
            ImmutableMap.of(
                firstUserId, ImmutableSet.of(roleId), secondUserId, ImmutableSet.of(roleId)));
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(roleId, Collections.emptyList()));

    Enforcer allowEnforcer = mock(Enforcer.class);
    Enforcer denyEnforcer = mock(Enforcer.class);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, denyEnforcer, ImmutableMap.of(metalake, snapshot));

    AuthorizationRequestContext requestContext = new AuthorizationRequestContext();
    authorizer.authorize(
        new UserPrincipal("first"), metalake, catalog, Privilege.Name.USE_CATALOG, requestContext);
    authorizer.authorize(
        new UserPrincipal("second"), metalake, catalog, Privilege.Name.USE_CATALOG, requestContext);

    verify(snapshot).getRoleIdToSecurableObjects();
    verify(allowEnforcer).addRoleForUser(String.valueOf(firstUserId), String.valueOf(roleId));
    verify(allowEnforcer).addRoleForUser(String.valueOf(secondUserId), String.valueOf(roleId));
  }

  @Test
  void testMissingRolePolicyEntryDoesNotBlockOtherRoles() {
    String metalake = "metalake";
    long userId = 1L;
    long missingRoleId = 10L;
    long healthyRoleId = 20L;
    long assetId = 101L;
    MetadataObject catalog = MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);
    AssetNode node = assetNode(assetId);
    NameIdentifier ident = MetadataObjectUtil.toEntityIdent(metalake, catalog);

    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getAssetNodeByIdent()).thenReturn(ImmutableMap.of(ident, node));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(assetId, node));
    when(snapshot.getUserNameToUserId()).thenReturn(ImmutableMap.of("user", userId));
    when(snapshot.getUserIdToRoleIds())
        .thenReturn(ImmutableMap.of(userId, ImmutableSet.of(missingRoleId, healthyRoleId)));
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(healthyRoleId, Collections.emptyList()));

    Enforcer allowEnforcer = mock(Enforcer.class);
    Enforcer denyEnforcer = mock(Enforcer.class);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, denyEnforcer, ImmutableMap.of(metalake, snapshot));

    authorizer.authorize(
        new UserPrincipal("user"),
        metalake,
        catalog,
        Privilege.Name.USE_CATALOG,
        new AuthorizationRequestContext());

    verify(allowEnforcer).addRoleForUser(String.valueOf(userId), String.valueOf(missingRoleId));
    verify(denyEnforcer).addRoleForUser(String.valueOf(userId), String.valueOf(missingRoleId));
    verify(allowEnforcer).addRoleForUser(String.valueOf(userId), String.valueOf(healthyRoleId));
    verify(denyEnforcer).addRoleForUser(String.valueOf(userId), String.valueOf(healthyRoleId));
  }

  @Test
  void testHierarchicalSchemaUsesAncestorPrivilege() {
    String metalake = "metalake";
    String catalog = "catalog";
    long userId = 1L;
    long ancestorId = 101L;
    MetadataObject ancestorSchema = MetadataObjects.of(catalog, "top", MetadataObject.Type.SCHEMA);
    MetadataObject childSchema =
        MetadataObjects.of(catalog, "top:child", MetadataObject.Type.SCHEMA);
    AssetNode ancestorNode = assetNode(ancestorId, MetadataObject.Type.SCHEMA);
    AssetNode childNode = assetNode(-1L, MetadataObject.Type.SCHEMA);

    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getAssetNodeByIdent())
        .thenReturn(
            ImmutableMap.of(
                MetadataObjectUtil.toEntityIdent(metalake, ancestorSchema),
                ancestorNode,
                MetadataObjectUtil.toEntityIdent(metalake, childSchema),
                childNode));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(ancestorId, ancestorNode));
    when(snapshot.getUserNameToUserId()).thenReturn(ImmutableMap.of("user", userId));
    when(snapshot.getUserIdToRoleIds()).thenReturn(Collections.emptyMap());
    when(snapshot.getRoleIdToSecurableObjects()).thenReturn(Collections.emptyMap());

    Enforcer allowEnforcer = mock(Enforcer.class);
    when(allowEnforcer.enforce(
            String.valueOf(userId),
            MetadataObject.Type.SCHEMA.name(),
            String.valueOf(ancestorId),
            Privilege.Name.USE_SCHEMA.name()))
        .thenReturn(true);
    Enforcer denyEnforcer = mock(Enforcer.class);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    authorizer.initialize(allowEnforcer, denyEnforcer, ImmutableMap.of(metalake, snapshot));

    boolean authorized =
        authorizer.authorize(
            new UserPrincipal("user"),
            metalake,
            childSchema,
            Privilege.Name.USE_SCHEMA,
            new AuthorizationRequestContext());

    assertTrue(authorized);
    verify(allowEnforcer, never())
        .enforce(
            String.valueOf(userId),
            MetadataObject.Type.SCHEMA.name(),
            "-1",
            Privilege.Name.USE_SCHEMA.name());
  }

  private static AssetNode assetNode(long id) {
    return assetNode(id, MetadataObject.Type.CATALOG);
  }

  private static AssetNode assetNode(long id, MetadataObject.Type type) {
    AssetNode node = mock(AssetNode.class);
    when(node.getId()).thenReturn(id);
    when(node.getType()).thenReturn(type);
    when(node.getOwners()).thenReturn(Collections.emptySet());
    return node;
  }
}
