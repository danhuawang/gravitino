/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetNormalizeDispatcher;
import org.apache.gravitino.meta.FilesetEntity;

public class DatastratoFilesetNormalizeDispatcher extends FilesetNormalizeDispatcher
    implements DatastratoFilesetDispatcher {

  private final DatastratoFilesetDispatcher dispatcher;

  public DatastratoFilesetNormalizeDispatcher(
      DatastratoFilesetDispatcher dispatcher, CatalogManager catalogManager) {
    super(dispatcher, catalogManager);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<FilesetEntity> listEntities(Namespace namespace) {
    // since the entities in the store are normalized, we can return them directly
    return dispatcher.listEntities(namespace);
  }
}
