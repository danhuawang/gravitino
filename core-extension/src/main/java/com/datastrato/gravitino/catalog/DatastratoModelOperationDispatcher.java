/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.MODEL;

import java.io.IOException;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.ModelOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoModelOperationDispatcher extends ModelOperationDispatcher
    implements DatastratoModelDispatcher {

  public DatastratoModelOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  @Override
  public List<ModelEntity> listEntities(Namespace namespace) {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () -> {
          try {
            return store.list(namespace, ModelEntity.class, MODEL);
          } catch (NoSuchEntityException e) {
            throw new NoSuchSchemaException("Schema does not exist: %s", namespace);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
