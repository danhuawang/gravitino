/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
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

  @Override
  public Map<String, Object>[] preview(
      NameIdentifier identifier, Entity.EntityType type, int resultLimit)
      throws DataPreviewSensitiveTableException {
    return dispatcher.preview(identifier, type, resultLimit);
  }
}
