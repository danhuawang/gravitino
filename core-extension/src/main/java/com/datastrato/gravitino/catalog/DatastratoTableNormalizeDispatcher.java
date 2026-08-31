/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.catalog.CapabilityHelpers.applyCaseSensitive;
import static org.apache.gravitino.catalog.CapabilityHelpers.getCapability;

import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.Column;

public class DatastratoTableNormalizeDispatcher extends TableNormalizeDispatcher
    implements DatastratoTableDispatcher {
  private final DatastratoTableDispatcher dispatcher;
  private final CatalogManager catalogManager;

  public DatastratoTableNormalizeDispatcher(
      DatastratoTableDispatcher dispatcher, CatalogManager catalogManager) {
    super(dispatcher, catalogManager);
    this.dispatcher = dispatcher;
    this.catalogManager = catalogManager;
  }

  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    Capability capabilities = getCapability(NameIdentifier.of(namespace.levels()), catalogManager);
    Namespace caseSensitiveNs = applyCaseSensitive(namespace, Capability.Scope.TABLE, capabilities);
    // since the entities in the store are normalized, we can return them directly
    return dispatcher.listEntities(caseSensitiveNs);
  }

  @Override
  public Map<String, Object>[] preview(
      NameIdentifier identifier, Entity.EntityType type, int resultLimit, Column[] columns)
      throws DataPreviewSensitiveTableException {
    Capability capabilities = getCapability(identifier, catalogManager);
    NameIdentifier caseSensitiveIdentifier =
        applyCaseSensitive(identifier, Capability.Scope.TABLE, capabilities);

    return dispatcher.preview(caseSensitiveIdentifier, type, resultLimit, columns);
  }
}
