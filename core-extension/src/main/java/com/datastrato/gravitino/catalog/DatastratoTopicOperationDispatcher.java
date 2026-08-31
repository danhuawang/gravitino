/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.TOPIC;

import java.io.IOException;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.TopicOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoTopicOperationDispatcher extends TopicOperationDispatcher
    implements DatastratoTopicDispatcher {

  /**
   * Creates a new TopicOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for catalog operations.
   * @param store The EntityStore instance to be used for catalog operations.
   * @param idGenerator The IdGenerator instance to be used for catalog operations.
   */
  public DatastratoTopicOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  @Override
  public List<TopicEntity> listEntities(Namespace namespace) {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () -> {
          try {
            return store.list(namespace, TopicEntity.class, TOPIC);
          } catch (NoSuchEntityException e) {
            throw new NoSuchSchemaException("Schema does not exist: %s", namespace);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
