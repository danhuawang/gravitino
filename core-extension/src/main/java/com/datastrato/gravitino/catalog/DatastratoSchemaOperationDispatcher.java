/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.SCHEMA;

import java.io.IOException;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoSchemaOperationDispatcher extends SchemaOperationDispatcher
    implements DatastratoSchemaDispatcher {
  /**
   * Creates a new SchemaOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for schema operations.
   * @param store The EntityStore instance to be used for schema operations.
   * @param idGenerator The IdGenerator instance to be used for schema operations.
   */
  public DatastratoSchemaOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  @Override
  public List<SchemaEntity> listEntities(Namespace namespace) {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () -> {
          try {
            return store.list(namespace, SchemaEntity.class, SCHEMA);
          } catch (NoSuchEntityException e) {
            throw new NoSuchCatalogException("Catalog does not exist: %s", namespace);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
