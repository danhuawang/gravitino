/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.TABLE;

import java.io.IOException;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoTableOperationDispatcher extends TableOperationDispatcher
    implements DatastratoTableDispatcher {

  /**
   * Creates a new TableOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for table operations.
   * @param store The EntityStore instance to be used for table operations.
   * @param idGenerator The IdGenerator instance to be used for table operations.
   */
  public DatastratoTableOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    try {
      return store.list(namespace, TableEntity.class, TABLE);
    } catch (NoSuchEntityException e) {
      throw new NoSuchSchemaException("Schema does not exist: " + namespace);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
