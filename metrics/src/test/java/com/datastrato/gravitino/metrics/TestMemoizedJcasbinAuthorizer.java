/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.metrics;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.casbin.jcasbin.main.Enforcer;
import org.junit.jupiter.api.Test;

class TestMemoizedJcasbinAuthorizer {

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

  private static AssetNode assetNode(long id) {
    AssetNode node = mock(AssetNode.class);
    when(node.getId()).thenReturn(id);
    when(node.getType()).thenReturn(MetadataObject.Type.CATALOG);
    when(node.getOwners()).thenReturn(Collections.emptySet());
    return node;
  }
}
