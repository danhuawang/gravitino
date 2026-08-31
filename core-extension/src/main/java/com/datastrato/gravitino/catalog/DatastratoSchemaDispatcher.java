/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.meta.SchemaEntity;

public interface DatastratoSchemaDispatcher
    extends SchemaDispatcher, EntityOperations<SchemaEntity> {

  /**
   * Returns whether the catalog identified by the namespace supports hierarchical schemas.
   *
   * @param namespace A namespace within the catalog.
   * @return {@code true} if the catalog supports hierarchical schemas, otherwise {@code false}.
   */
  default boolean supportsHierarchicalSchema(Namespace namespace) {
    return false;
  }
}
