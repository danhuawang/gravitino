/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.catalog.CapabilityHelpers.getCapability;

import java.util.List;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaNormalizeDispatcher;
import org.apache.gravitino.meta.SchemaEntity;

public class DatastratoSchemaNormalizeDispatcher extends SchemaNormalizeDispatcher
    implements DatastratoSchemaDispatcher {
  private final DatastratoSchemaDispatcher dispatcher;
  private final CatalogManager catalogManager;

  public DatastratoSchemaNormalizeDispatcher(
      DatastratoSchemaDispatcher dispatcher, CatalogManager catalogManager) {
    super(dispatcher, catalogManager);
    this.dispatcher = dispatcher;
    this.catalogManager = catalogManager;
  }

  /**
   * Returns whether the catalog identified by the namespace supports hierarchical schemas.
   *
   * @param namespace A namespace within the catalog.
   * @return {@code true} if the catalog supports hierarchical schemas, otherwise {@code false}.
   */
  @Override
  public boolean supportsHierarchicalSchema(Namespace namespace) {
    return getCapability(NameIdentifier.of(namespace.levels()), catalogManager)
        .supportsHierarchicalSchema()
        .supported();
  }

  @Override
  public List<SchemaEntity> listEntities(Namespace namespace) {
    // since the entities in the store are normalized, we can return them directly
    return dispatcher.listEntities(namespace);
  }
}
