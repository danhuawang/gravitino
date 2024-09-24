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

  @Override
  public List<SchemaEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }
}
