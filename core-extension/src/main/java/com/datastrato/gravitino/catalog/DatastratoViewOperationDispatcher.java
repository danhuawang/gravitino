/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.VIEW;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.ViewOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoViewOperationDispatcher extends ViewOperationDispatcher
    implements DatastratoViewDispatcher {

  /**
   * Creates a new DatastratoViewOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for view operations.
   * @param store The EntityStore instance to be used for view operations.
   * @param idGenerator The IdGenerator instance to be used for view operations.
   */
  public DatastratoViewOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  /**
   * Creates a new DatastratoViewOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for view operations.
   * @param store The EntityStore instance to be used for view operations.
   * @param idGenerator The IdGenerator instance to be used for view operations.
   * @param schemaDispatcherSupplier The SchemaDispatcher supplier to ensure schemas are imported.
   */
  public DatastratoViewOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore store,
      IdGenerator idGenerator,
      Supplier<SchemaDispatcher> schemaDispatcherSupplier) {
    super(catalogManager, store, idGenerator, schemaDispatcherSupplier);
  }

  @Override
  public List<ViewEntity> listEntities(Namespace namespace) {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () -> {
          try {
            return store.list(namespace, ViewEntity.class, VIEW);
          } catch (NoSuchEntityException e) {
            throw new NoSuchSchemaException("Schema does not exist: %s", namespace);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
