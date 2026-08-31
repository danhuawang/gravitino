/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.hook.ModelHookDispatcher;
import org.apache.gravitino.meta.ModelEntity;

public class DatastratoModelHookDispatcher extends ModelHookDispatcher
    implements DatastratoModelDispatcher {
  private final DatastratoModelDispatcher dispatcher;

  public DatastratoModelHookDispatcher(DatastratoModelDispatcher dispatcher) {
    super(dispatcher);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<ModelEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }
}
