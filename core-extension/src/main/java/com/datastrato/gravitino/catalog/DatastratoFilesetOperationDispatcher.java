/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.FILESET;

import java.io.IOException;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoFilesetOperationDispatcher extends FilesetOperationDispatcher
    implements DatastratoFilesetDispatcher {

  /**
   * Creates a new FilesetOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for fileset operations.
   * @param store The EntityStore instance to be used for fileset operations.
   * @param idGenerator The IdGenerator instance to be used for fileset operations.
   */
  public DatastratoFilesetOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  @Override
  public List<FilesetEntity> listEntities(Namespace namespace) {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () -> {
          try {
            return store.list(namespace, FilesetEntity.class, FILESET);
          } catch (NoSuchEntityException e) {
            throw new NoSuchSchemaException("Schema does not exist: %s", namespace);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
