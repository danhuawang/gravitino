/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.metrics.listener;

import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.AlterMetalakeEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Marks dashboard metrics dirty after relevant successful metadata mutations. */
public class DashboardMetricsEventListener implements EventListenerPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(DashboardMetricsEventListener.class);

  private static final Set<OperationType> RELEVANT_OPERATIONS =
      EnumSet.of(
          // Metalake operations.
          OperationType.CREATE_METALAKE,
          OperationType.ALTER_METALAKE,
          OperationType.ENABLE_METALAKE,
          // Catalog operations.
          OperationType.CREATE_CATALOG,
          OperationType.DROP_CATALOG,
          OperationType.ALTER_CATALOG,
          OperationType.ENABLE_CATALOG,
          OperationType.DISABLE_CATALOG,
          // Schema operations.
          OperationType.CREATE_SCHEMA,
          OperationType.DROP_SCHEMA,
          OperationType.ALTER_SCHEMA,
          // Table operations.
          OperationType.CREATE_TABLE,
          OperationType.DROP_TABLE,
          OperationType.PURGE_TABLE,
          OperationType.ALTER_TABLE,
          OperationType.RENAME_TABLE,
          OperationType.REGISTER_TABLE,
          // Fileset operations.
          OperationType.DROP_FILESET,
          OperationType.ALTER_FILESET,
          OperationType.CREATE_FILESET,
          // Topic operations.
          OperationType.CREATE_TOPIC,
          OperationType.ALTER_TOPIC,
          OperationType.DROP_TOPIC,
          // Model operations.
          OperationType.REGISTER_MODEL,
          OperationType.DELETE_MODEL,
          OperationType.ALTER_MODEL,
          OperationType.REGISTER_AND_LINK_MODEL_VERSION,
          // Tag operations.
          OperationType.CREATE_TAG,
          OperationType.DELETE_TAG,
          OperationType.ALTER_TAG,
          OperationType.ASSOCIATE_TAGS_FOR_METADATA_OBJECT,
          // User operations.
          OperationType.ADD_USER,
          OperationType.REMOVE_USER,
          OperationType.REMOVE_USER_BY_EXTERNAL_ID,
          OperationType.REMOVE_USER_BY_ID,
          OperationType.ALTER_USER,
          OperationType.ENABLE_USER,
          OperationType.DISABLE_USER,
          OperationType.GRANT_USER_ROLES,
          OperationType.REVOKE_USER_ROLES,
          // Group operations.
          OperationType.ADD_GROUP,
          OperationType.REMOVE_GROUP,
          OperationType.REMOVE_GROUP_BY_EXTERNAL_ID,
          OperationType.REMOVE_GROUP_BY_ID,
          OperationType.ALTER_GROUP,
          OperationType.GRANT_GROUP_ROLES,
          OperationType.REVOKE_GROUP_ROLES,
          // Role and privilege operations.
          OperationType.CREATE_ROLE,
          OperationType.DELETE_ROLE,
          OperationType.GRANT_PRIVILEGES,
          OperationType.REVOKE_PRIVILEGES,
          OperationType.OVERRIDE_PRIVILEGES,
          // Ownership operations.
          OperationType.SET_OWNER);

  private MetricDataService metricDataService;

  @Override
  public void init(Map<String, String> properties) throws RuntimeException {
    metricDataService = MetricDataService.getInstance();
  }

  @Override
  public void start() throws RuntimeException {
    // Shared metadata and metric services are already initialized by the server.
  }

  @Override
  public void stop() throws RuntimeException {
    // This listener owns no resources that require shutdown.
  }

  @Override
  public Mode mode() {
    return Mode.ASYNC_ISOLATED;
  }

  @Override
  public void onPostEvent(Event event) {
    if (event.operationStatus() != OperationStatus.SUCCESS
        || !RELEVANT_OPERATIONS.contains(event.operationType())) {
      return;
    }

    String metalakeName = metalakeName(event);
    if (metalakeName == null) {
      LOG.warn(
          "Skipping dashboard metric dirty marker because event {} has no metalake identifier",
          event.operationType());
      return;
    }

    try {
      long metalakeId = MetalakeMetaService.getInstance().getMetalakeIdByName(metalakeName);
      metricDataService.markMetalakeDirty(metalakeId, event.eventTime());
    } catch (Exception e) {
      LOG.warn(
          "Failed to mark dashboard metrics dirty for metalake {} after {}",
          metalakeName,
          event.operationType(),
          e);
    }
  }

  @Nullable
  private static String metalakeName(Event event) {
    if (event instanceof AlterMetalakeEvent) {
      return ((AlterMetalakeEvent) event).updatedMetalakeInfo().name();
    }

    NameIdentifier identifier = event.identifier();
    if (identifier == null) {
      return null;
    }
    return identifier.namespace().isEmpty() ? identifier.name() : identifier.namespace().level(0);
  }
}
