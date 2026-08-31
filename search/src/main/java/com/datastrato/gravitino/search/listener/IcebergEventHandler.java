/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.utils.IcebergEventUtils;
import java.lang.reflect.Method;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Iceberg REST events (IcebergTableEvent and IcebergNamespaceEvent subtypes) dispatched by
 * the Iceberg REST auxiliary service.
 *
 * <p>These event classes live inside the Iceberg REST server's isolated class loader, so they are
 * NOT accessible as compile-time types here. Instead, class identity is checked by walking the
 * superclass chain by name, and Iceberg-specific fields (e.g. RenameTableRequest) are read via
 * reflection. All other types used (OperationType, NameIdentifier) are shared classes loaded from
 * the application class loader, so they are safe to reference directly.
 */
public class IcebergEventHandler implements EventHandler {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergEventHandler.class);

  private final SearchService searchService;

  public IcebergEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    OperationType opType = IcebergEventUtils.getOperationType(event);
    if (opType == null) {
      return;
    }

    NameIdentifier identifier = event.identifier();

    if (IcebergEventUtils.isSubclassOf(event, IcebergEventUtils.ICEBERG_TABLE_EVENT_CLASS)) {
      handleIcebergTableEvent(event, identifier, opType);
    } else if (IcebergEventUtils.isSubclassOf(
        event, IcebergEventUtils.ICEBERG_NAMESPACE_EVENT_CLASS)) {
      handleIcebergNamespaceEvent(identifier, opType);
    }
  }

  private void handleIcebergTableEvent(
      Event event, NameIdentifier identifier, OperationType opType) {
    switch (opType) {
      case CREATE_TABLE:
      case ALTER_TABLE:
      case REGISTER_TABLE:
        searchService.synchronizeMetadata(identifier, EntityType.TABLE, false);
        break;
      case DROP_TABLE:
        searchService.removeMetadata(identifier, EntityType.TABLE, false);
        break;
      case RENAME_TABLE:
        handleRenameTable(event, identifier);
        break;
      default:
        break;
    }
  }

  private void handleRenameTable(Event event, NameIdentifier sourceIdentifier) {
    // RenameTableRequest and TableIdentifier live in iceberg-core/iceberg-api inside the
    // Iceberg REST isolated classloader — access them via reflection only.
    try {
      // event.renameTableRequest() → RenameTableRequest (isolated CL)
      Method reqMethod = event.getClass().getMethod("renameTableRequest");
      Object renameRequest = reqMethod.invoke(event);

      // renameRequest.destination() → TableIdentifier (isolated CL)
      Method destMethod = renameRequest.getClass().getMethod("destination");
      Object destination = destMethod.invoke(renameRequest);

      // destination.name() → String
      String newName = (String) destination.getClass().getMethod("name").invoke(destination);

      // destination.namespace() → Namespace (isolated CL)  then  .levels() → String[]
      Object namespace = destination.getClass().getMethod("namespace").invoke(destination);
      String[] levels = (String[]) namespace.getClass().getMethod("levels").invoke(namespace);

      // Reconstruct Gravitino NameIdentifier:
      // [metalake, catalog] + destination.namespace().levels() + destination.name()
      String[] parts = new String[2 + levels.length + 1];
      parts[0] = sourceIdentifier.namespace().level(0); // metalake
      parts[1] = sourceIdentifier.namespace().level(1); // catalog
      System.arraycopy(levels, 0, parts, 2, levels.length);
      parts[parts.length - 1] = newName;
      NameIdentifier newIdentifier = NameIdentifier.of(parts);

      searchService.removeMetadata(sourceIdentifier, EntityType.TABLE, false);
      searchService.synchronizeMetadata(newIdentifier, EntityType.TABLE, false);
    } catch (Exception e) {
      LOG.warn(
          "Failed to handle Iceberg RENAME_TABLE event for {}, falling back to sync source",
          sourceIdentifier,
          e);
      // Best-effort: sync the source identifier so at least something is indexed
      searchService.synchronizeMetadata(sourceIdentifier, EntityType.TABLE, false);
    }
  }

  private void handleIcebergNamespaceEvent(NameIdentifier identifier, OperationType opType) {
    switch (opType) {
      case CREATE_SCHEMA:
      case ALTER_SCHEMA:
        searchService.synchronizeMetadata(identifier, EntityType.SCHEMA, false);
        break;
      case DROP_SCHEMA:
        searchService.removeMetadata(identifier, EntityType.SCHEMA, true);
        break;
      default:
        break;
    }
  }
}
