/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.utils.PermissionProjectionCache;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.CreateRoleEvent;
import org.apache.gravitino.listener.api.event.DeleteRoleEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.GrantPrivilegesEvent;
import org.apache.gravitino.listener.api.event.OverridePrivilegesEvent;
import org.apache.gravitino.listener.api.event.RevokePrivilegesEvent;
import org.apache.gravitino.utils.NameIdentifierUtil;

/**
 * Synchronizes Role search documents and the data permission projections affected by Role events.
 */
public class RoleEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a Role event handler.
   *
   * @param searchService The search service to update.
   */
  public RoleEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    String metalake = event.identifier().namespace().level(0);

    if (event instanceof CreateRoleEvent) {
      PermissionProjectionCache.invalidate(metalake);
      CreateRoleEvent createRoleEvent = (CreateRoleEvent) event;
      synchronizeRole(metalake, createRoleEvent.createdRoleInfo().roleName());
      createRoleEvent
          .createdRoleInfo()
          .securableObjects()
          .forEach(object -> synchronizeAffectedObject(metalake, object));
    } else if (event instanceof DeleteRoleEvent) {
      DeleteRoleEvent deleteRoleEvent = (DeleteRoleEvent) event;
      if (deleteRoleEvent.isExists()) {
        PermissionProjectionCache.invalidate(metalake);
        searchService.removeEntityByName(metalake, deleteRoleEvent.roleName(), EntityType.ROLE);
        reconcileMetalake(metalake);
      }
    } else if (event instanceof GrantPrivilegesEvent) {
      PermissionProjectionCache.invalidate(metalake);
      GrantPrivilegesEvent grantEvent = (GrantPrivilegesEvent) event;
      synchronizeRole(metalake, grantEvent.grantedRoleInfo().roleName());
      synchronizeAffectedObject(metalake, grantEvent.object());
    } else if (event instanceof RevokePrivilegesEvent) {
      PermissionProjectionCache.invalidate(metalake);
      RevokePrivilegesEvent revokeEvent = (RevokePrivilegesEvent) event;
      synchronizeRole(metalake, revokeEvent.revokedRoleInfo().roleName());
      synchronizeAffectedObject(metalake, revokeEvent.object());
    } else if (event instanceof OverridePrivilegesEvent) {
      PermissionProjectionCache.invalidate(metalake);
      OverridePrivilegesEvent overrideEvent = (OverridePrivilegesEvent) event;
      synchronizeRole(metalake, overrideEvent.updatedRoleInfo().roleName());
      // The event only contains the replacement scopes. A full reconciliation is needed to clear
      // projections for scopes that were removed by the override.
      reconcileMetalake(metalake);
    }
  }

  private void synchronizeRole(String metalake, String roleName) {
    searchService.synchronizeMetadata(
        NameIdentifierUtil.ofRole(metalake, roleName), EntityType.ROLE, false);
  }

  private void synchronizeAffectedObject(String metalake, MetadataObject object) {
    if (supportsPermissionProjection(object.type())) {
      searchService.synchronizeMetadata(metalake, object, true);
    }
  }

  private void reconcileMetalake(String metalake) {
    searchService.synchronizeMetadata(NameIdentifier.of(metalake), EntityType.METALAKE, true);
  }

  private static boolean supportsPermissionProjection(MetadataObject.Type type) {
    switch (type) {
      case METALAKE:
      case CATALOG:
      case SCHEMA:
      case TABLE:
      case FILESET:
      case TOPIC:
      case MODEL:
        return true;
      default:
        return false;
    }
  }
}
