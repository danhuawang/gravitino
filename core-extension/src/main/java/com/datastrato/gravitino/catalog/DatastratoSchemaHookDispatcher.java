/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.hook.SchemaHookDispatcher;
import org.apache.gravitino.meta.SchemaEntity;

public class DatastratoSchemaHookDispatcher extends SchemaHookDispatcher
    implements DatastratoSchemaDispatcher {
  private final DatastratoSchemaDispatcher dispatcher;

  public DatastratoSchemaHookDispatcher(DatastratoSchemaDispatcher dispatcher) {
    super(dispatcher);
    this.dispatcher = dispatcher;
  }

  /**
   * Returns whether the catalog identified by the namespace supports hierarchical schemas.
   *
   * @param namespace A namespace within the catalog.
   * @return {@code true} if the catalog supports hierarchical schemas, otherwise {@code false}.
   */
  @Override
  public boolean supportsHierarchicalSchema(Namespace namespace) {
    return dispatcher.supportsHierarchicalSchema(namespace);
  }

  @Override
  public List<SchemaEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }
}
