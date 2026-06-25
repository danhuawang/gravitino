/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.TABLE;

import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import com.datastrato.gravitino.preview.TrinoJdbcDataPreviewOperator;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableOperationDispatcher;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.storage.IdGenerator;

public class DatastratoTableOperationDispatcher extends TableOperationDispatcher
    implements DatastratoTableDispatcher {
  private final TrinoJdbcDataPreviewOperator trinoJdbcDataPreviewOperator;

  /**
   * Creates a new DatastratoTableOperationDispatcher instance with an explicit schema dispatcher
   * supplier. Use this constructor when wiring from the environment initializer so the supplier
   * captures the correct env instance rather than a static singleton.
   *
   * @param catalogManager The CatalogManager instance to be used for table operations.
   * @param store The EntityStore instance to be used for table operations.
   * @param idGenerator The IdGenerator instance to be used for table operations.
   * @param trinoJdbcDataPreviewOperator The trino jdbc preview operator for preview operations.
   * @param schemaDispatcherSupplier Supplier for the schema dispatcher used during schema imports.
   */
  public DatastratoTableOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore store,
      IdGenerator idGenerator,
      TrinoJdbcDataPreviewOperator trinoJdbcDataPreviewOperator,
      Supplier<SchemaDispatcher> schemaDispatcherSupplier) {
    super(catalogManager, store, idGenerator, schemaDispatcherSupplier);
    this.trinoJdbcDataPreviewOperator = trinoJdbcDataPreviewOperator;
  }

  /**
   * Creates a new DatastratoTableOperationDispatcher instance using the OSS default schema
   * dispatcher supplier ({@code GravitinoEnv.getInstance().schemaDispatcher()}). Prefer the
   * five-argument constructor when wiring from the enterprise environment initializer.
   *
   * @param catalogManager The CatalogManager instance to be used for table operations.
   * @param store The EntityStore instance to be used for table operations.
   * @param idGenerator The IdGenerator instance to be used for table operations.
   * @param trinoJdbcDataPreviewOperator The trino jdbc preview operator for preview operations.
   */
  public DatastratoTableOperationDispatcher(
      CatalogManager catalogManager,
      EntityStore store,
      IdGenerator idGenerator,
      TrinoJdbcDataPreviewOperator trinoJdbcDataPreviewOperator) {
    super(catalogManager, store, idGenerator);
    this.trinoJdbcDataPreviewOperator = trinoJdbcDataPreviewOperator;
  }

  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () -> {
          try {
            return store.list(namespace, TableEntity.class, TABLE);
          } catch (NoSuchEntityException e) {
            throw new NoSuchSchemaException("Schema does not exist: %s", namespace);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Override
  public Map<String, Object>[] preview(
      NameIdentifier identifier, Entity.EntityType type, int resultLimit, Column[] columns)
      throws DataPreviewSensitiveTableException {
    // If we use the table read lock, the trino connector will load table, it will cause
    // the dead lock.
    return trinoJdbcDataPreviewOperator.preview(identifier, type, resultLimit, columns);
  }
}
