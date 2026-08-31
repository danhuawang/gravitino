/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.hook.FilesetHookDispatcher;
import org.apache.gravitino.meta.FilesetEntity;

public class DatastratoFilesetHookDispatcher extends FilesetHookDispatcher
    implements DatastratoFilesetDispatcher {
  private final DatastratoFilesetDispatcher dispatcher;

  public DatastratoFilesetHookDispatcher(DatastratoFilesetDispatcher dispatcher) {
    super(dispatcher);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<FilesetEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }
}
