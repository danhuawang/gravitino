/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.catalog.CapabilityHelpers.applyCaseSensitive;
import static org.apache.gravitino.catalog.CapabilityHelpers.getCapability;

import java.util.List;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.TableNormalizeDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.meta.TableEntity;

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
}
