/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino.metrics;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.server.authorization.jcasbin.GravitinoAdapter;
import org.apache.gravitino.server.authorization.jcasbin.JcasbinAuthorizer;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.main.SyncedEnforcer;
import org.casbin.jcasbin.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Jcasbin implementation of GravitinoAuthorizer. */
public class MemoizedJcasbinAuthorizer implements GravitinoAuthorizer {

  private static final Logger LOG = LoggerFactory.getLogger(MemoizedJcasbinAuthorizer.class);

  /** Jcasbin enforcer is used for metadata authorization. */
  private Enforcer allowEnforcer;

  /** Jcasbin deny enforcer is used for metadata authorization. */
  private Enforcer denyEnforcer;

  /** allow internal authorizer */
  private InternalAuthorizer allowInternalAuthorizer;

  /** deny internal authorizer */
  private InternalAuthorizer denyInternalAuthorizer;

  private Map<String, MetalakeSnapshot> metalakeSnapshots;

  /** Roles whose policies have been loaded from the immutable metalake snapshot. */
  private final Set<Long> loadedRoles = new HashSet<>();

  /** User-to-role relations that have been loaded into both enforcers. */
  private final Map<Long, Set<Long>> loadedUserRoles = new HashMap<>();

  @Override
  public void initialize() {
    initialize(
        new SyncedEnforcer(getModel("/jcasbin_model.conf"), new GravitinoAdapter()),
        new SyncedEnforcer(getModel("/jcasbin_model.conf"), new GravitinoAdapter()),
        MetricsCollector.getInstance().getMetalakeSnapshots());
  }

  @VisibleForTesting
  void initialize(
      Enforcer allowEnforcer,
      Enforcer denyEnforcer,
      Map<String, MetalakeSnapshot> metalakeSnapshots) {
    this.allowEnforcer = allowEnforcer;
    this.allowInternalAuthorizer = new InternalAuthorizer(allowEnforcer);
    this.denyEnforcer = denyEnforcer;
    this.denyInternalAuthorizer = new InternalAuthorizer(denyEnforcer);
    this.metalakeSnapshots = metalakeSnapshots;
    loadedRoles.clear();
    loadedUserRoles.clear();
  }

  private Model getModel(String modelFilePath) {
    Model model = new Model();
    try (InputStream modelStream = JcasbinAuthorizer.class.getResourceAsStream(modelFilePath)) {
      Preconditions.checkNotNull(modelStream, "Jcasbin model file can not found.");
      String modelData = IOUtils.toString(modelStream, StandardCharsets.UTF_8);
      model.loadModelFromText(modelData);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return model;
  }

  @Override
  public boolean authorize(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      Privilege.Name privilege,
      AuthorizationRequestContext requestContext) {
    boolean result =
        allowInternalAuthorizer.authorizeInternal(
            principal, metalake, metadataObject, privilege.name());
    LOG.debug(
        "principal {},metalake {},metadata object {},privilege {}, result {}",
        principal,
        metalake,
        metadataObject,
        privilege,
        result);
    return result;
  }

  @Override
  public boolean deny(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      Privilege.Name privilege,
      AuthorizationRequestContext requestContext) {
    boolean result =
        denyInternalAuthorizer.authorizeInternal(
            principal, metalake, metadataObject, privilege.name());
    LOG.debug(
        "principal {},metalake {},metadata object {},privilege {},deny result {}",
        principal,
        metalake,
        metadataObject,
        privilege,
        result);
    return result;
  }

  @Override
  public boolean isOwner(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      AuthorizationRequestContext requestContext) {
    boolean result =
        allowInternalAuthorizer.authorizeInternal(
            principal, metalake, metadataObject, AuthConstants.OWNER);
    LOG.debug(
        "principal {},metalake {},metadata object {},privilege {},deny result {}",
        principal,
        metalake,
        metadataObject,
        "OWNER",
        result);
    return result;
  }

  @Override
  public boolean isServiceAdmin() {
    // since this authorizer is used for metric job to filter metadata, so it should not reach here.
    throw new UnsupportedOperationException("Unsupported operation.");
  }

  @Override
  public boolean isMetalakeUser(String metalake, AuthorizationRequestContext requestContext) {
    // since this authorizer is used for metric job to filter metadata, so it should not reach here.
    throw new UnsupportedOperationException("Unsupported operation.");
  }

  @Override
  public boolean isSelf(
      Entity.EntityType type,
      NameIdentifier nameIdentifier,
      AuthorizationRequestContext requestContext) {
    // since this authorizer is used for metric job to filter metadata, so it should not reach here.
    throw new UnsupportedOperationException("Unsupported operation.");
  }

  @Override
  public boolean hasSetOwnerPermission(
      String metalake, String type, String fullName, AuthorizationRequestContext requestContext) {
    // since this authorizer is used for metric job to filter metadata, so it should not reach here.
    throw new UnsupportedOperationException("Unsupported operation.");
  }

  @Override
  public boolean hasMetadataPrivilegePermission(
      String metalake, String type, String fullName, AuthorizationRequestContext requestContext) {
    // since this authorizer is used for metric job to filter metadata, so it should not reach here.
    throw new UnsupportedOperationException("Unsupported operation.");
  }

  @Override
  public void handleRolePrivilegeChange(Long roleId) {
    // do nothing since this authorizer is used for daily metric job, not real time request.
  }

  @Override
  public void handleMetadataOwnerChange(
      String metalake, Long oldOwnerId, NameIdentifier nameIdentifier, Entity.EntityType type) {
    // do nothing since this authorizer is used for daily metric job, not real time request.
  }

  @Override
  public void close() throws IOException {}

  private class InternalAuthorizer {

    Enforcer enforcer;

    public InternalAuthorizer(Enforcer enforcer) {
      this.enforcer = enforcer;
    }

    private boolean authorizeInternal(
        Principal principal, String metalake, MetadataObject metadataObject, String privilege) {
      String username = principal.getName();
      NameIdentifier entityIdent = MetadataObjectUtil.toEntityIdent(metalake, metadataObject);
      AssetNode assetNode = metalakeSnapshots.get(metalake).getAssetNodeByIdent().get(entityIdent);
      if (assetNode == null) {
        LOG.warn(
            "Can not find the asset node for metadata object {}, metalake {}",
            metadataObject.fullName(),
            metalake);
        return false;
      }
      return loadPrivilegeAndAuthorize(username, metalake, assetNode, privilege);
    }

    private boolean loadPrivilegeAndAuthorize(
        String username, String metalake, AssetNode assetNode, String privilege) {
      Long userId = metalakeSnapshots.get(metalake).getUserNameToUserId().get(username);
      Long metadataId = assetNode.getId();

      if (userId == null || metadataId == -1) {
        LOG.debug("Can not get user id or entity id, user id {}, entity id {}", userId, metadataId);
        return false;
      }

      loadPrivilege(metalake, userId, metadataId);
      return authorizeByJcasbin(userId, assetNode.getType(), metadataId, privilege);
    }

    private boolean authorizeByJcasbin(
        Long userId, MetadataObject.Type metadataObjectType, Long metadataId, String privilege) {
      return enforcer.enforce(
          String.valueOf(userId),
          String.valueOf(metadataObjectType),
          String.valueOf(metadataId),
          privilege);
    }
  }

  private void loadPrivilege(String metalake, Long userId, Long metadataObjectId) {
    try {
      loadRolePrivilege(metalake, userId);
    } catch (Exception e) {
      LOG.warn("Can not load role privilege for user id {} in metalake {}", userId, metalake, e);
    }

    try {
      loadOwnerPolicy(metalake, metadataObjectId);
    } catch (Exception e) {
      LOG.warn(
          "Can not load owner policy for metadata id {} in metalake {}",
          metadataObjectId,
          metalake,
          e);
    }
  }

  private synchronized void loadRolePrivilege(String metalake, Long userId) {
    Set<Long> roleIds = metalakeSnapshots.get(metalake).getUserIdToRoleIds().get(userId);
    if (roleIds == null) {
      return;
    }
    Set<Long> loadedRoleIds = loadedUserRoles.computeIfAbsent(userId, ignored -> new HashSet<>());

    roleIds.forEach(
        roleId -> {
          if (!loadedRoles.contains(roleId)) {
            loadPolicyByRoleId(metalake, roleId);
            loadedRoles.add(roleId);
          }
          if (!loadedRoleIds.contains(roleId)) {
            allowEnforcer.addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
            denyEnforcer.addRoleForUser(String.valueOf(userId), String.valueOf(roleId));
            loadedRoleIds.add(roleId);
          }
        });
  }

  private void loadOwnerPolicy(String metalake, Long metadataId) {
    try {
      AssetNode assetNode = metalakeSnapshots.get(metalake).getAssetNodeById().get(metadataId);
      for (Owner owner : assetNode.getOwners()) {
        if (owner.type() == Owner.Type.USER) {
          long userId = metalakeSnapshots.get(metalake).getUserNameToUserId().get(owner.name());
          ImmutableList<String> policy =
              ImmutableList.of(
                  String.valueOf(userId),
                  String.valueOf(assetNode.getType()),
                  String.valueOf(metadataId),
                  AuthConstants.OWNER,
                  AuthConstants.ALLOW);
          allowEnforcer.addPolicy(policy);
        } else {
          LOG.warn(
              "Unsupported owner type {} for owner {}, asset {}",
              owner.type(),
              owner.name(),
              assetNode.getNameIdent());
        }
      }
    } catch (Exception e) {
      LOG.warn("Can not load metadata owner", e);
    }
  }

  private void loadPolicyByRoleId(String metalakeName, Long roleId) {
    MetalakeSnapshot metalakeSnapshot = metalakeSnapshots.get(metalakeName);
    List<SecurableObject> securableObjects =
        metalakeSnapshot
            .getRoleIdToSecurableObjects()
            .getOrDefault(roleId, Collections.emptyList());

    for (SecurableObject securableObject : securableObjects) {
      NameIdentifier ident = MetadataObjectUtil.toEntityIdent(metalakeName, securableObject);
      // the id will not be null since the securable object is transformed from assetNodeByIdent of
      // metalake snapshot.
      AssetNode assetNode = metalakeSnapshot.getAssetNodeByIdent().get(ident);
      if (assetNode == null) {
        LOG.warn("AssetNode not found for ident {} in metalake {}", ident, metalakeName);
        continue;
      }
      long id = assetNode.getId();
      for (Privilege privilege : securableObject.privileges()) {
        Privilege.Condition condition = privilege.condition();
        if (AuthConstants.DENY.equalsIgnoreCase(condition.name())) {
          denyEnforcer.addPolicy(
              String.valueOf(roleId),
              securableObject.type().name(),
              String.valueOf(id),
              privilege.name().name().toUpperCase(),
              AuthConstants.ALLOW);
        }
        allowEnforcer.addPolicy(
            String.valueOf(roleId),
            securableObject.type().name(),
            String.valueOf(id),
            privilege.name().name().toUpperCase(),
            condition.name().toLowerCase());
      }
    }
  }
}
