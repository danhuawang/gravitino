/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.catalog.CapabilityHelpers.applyCaseSensitive;
import static org.apache.gravitino.catalog.CapabilityHelpers.getCapability;

import java.util.List;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.ModelNormalizeDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.meta.ModelEntity;

public class DatastratoModelNormalizeDispatcher extends ModelNormalizeDispatcher
    implements DatastratoModelDispatcher {

  private final DatastratoModelDispatcher dispatcher;
  private final CatalogManager catalogManager;

  public DatastratoModelNormalizeDispatcher(
      DatastratoModelDispatcher dispatcher, CatalogManager catalogManager) {
    super(dispatcher, catalogManager);
    this.dispatcher = dispatcher;
    this.catalogManager = catalogManager;
  }

  @Override
  public List<ModelEntity> listEntities(Namespace namespace) {
    Capability capabilities = getCapability(NameIdentifier.of(namespace.levels()), catalogManager);
    Namespace caseSensitiveNs = applyCaseSensitive(namespace, Capability.Scope.MODEL, capabilities);
    // since the entities in the store are normalized, we can return them directly
    return dispatcher.listEntities(caseSensitiveNs);
  }
}
