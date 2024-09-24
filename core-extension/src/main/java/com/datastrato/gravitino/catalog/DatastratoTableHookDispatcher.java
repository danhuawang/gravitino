/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.hook.TableHookDispatcher;
import org.apache.gravitino.meta.TableEntity;

public class DatastratoTableHookDispatcher extends TableHookDispatcher
    implements DatastratoTableDispatcher {
  private final DatastratoTableDispatcher dispatcher;

  public DatastratoTableHookDispatcher(DatastratoTableDispatcher dispatcher) {
    super(dispatcher);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }
}
