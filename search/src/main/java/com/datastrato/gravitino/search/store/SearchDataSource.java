/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.Entity;

/**
 * * Interface for a data source that provides search entities. Implementations of this interface
 * should provide a way to retrieve a batch of search entities.
 */
public interface SearchDataSource {
  /**
   * Retrieves the next batch of search entities.
   *
   * @return a {@link Result} containing a list of {@link SearchEntityDTO} and the type of entity.
   */
  Result nextBatch();

  class Result {
    private final List<SearchEntityDTO> entities;
    private final Entity.EntityType entityType;

    public Result(List<SearchEntityDTO> entities, Entity.EntityType entityType) {
      this.entities = entities;
      this.entityType = entityType;
    }

    public List<SearchEntityDTO> entities() {
      return entities;
    }

    public Entity.EntityType entityType() {
      return entityType;
    }

    public static Result empty() {
      return new Result(Collections.emptyList(), null);
    }

    public boolean isEmpty() {
      return entities.isEmpty() || entityType == null;
    }
  }
}
