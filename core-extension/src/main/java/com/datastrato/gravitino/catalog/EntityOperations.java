/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Namespace;

/**
 * For Web API to operate entities in Gravitino storage.
 *
 * @param <E> The entity type.
 */
public interface EntityOperations<E extends Entity & Auditable> {
  /**
   * List all entities in the namespace.
   *
   * @param namespace The namespace to list the entities under it.
   * @return The list of entities. Should return an empty list if no entities are found.
   */
  List<E> listEntities(Namespace namespace);
}
