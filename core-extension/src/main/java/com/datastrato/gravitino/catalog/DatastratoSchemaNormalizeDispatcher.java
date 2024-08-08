/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaNormalizeDispatcher;
import org.apache.gravitino.meta.SchemaEntity;

public class DatastratoSchemaNormalizeDispatcher extends SchemaNormalizeDispatcher
    implements DatastratoSchemaDispatcher {
  private final DatastratoSchemaDispatcher dispatcher;

  public DatastratoSchemaNormalizeDispatcher(
      DatastratoSchemaDispatcher dispatcher, CatalogManager catalogManager) {
    super(dispatcher, catalogManager);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<SchemaEntity> listEntities(Namespace namespace) {
    // since the entities in the store are normalized, we can return them directly
    return dispatcher.listEntities(namespace);
  }
}
