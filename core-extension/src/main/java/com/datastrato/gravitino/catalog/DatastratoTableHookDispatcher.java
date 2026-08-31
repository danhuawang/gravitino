/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.hook.TableHookDispatcher;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.Column;

public class DatastratoTableHookDispatcher extends TableHookDispatcher
    implements DatastratoTableDispatcher {
  private final DatastratoTableDispatcher dispatcher;

  /**
   * Creates a Datastrato table hook dispatcher.
   *
   * @param dispatcher the underlying table dispatcher
   * @param ownerDispatcher supplies the owner dispatcher, or {@code null} when authorization is
   *     disabled
   * @param catalogManager the catalog manager used to apply catalog capabilities
   */
  public DatastratoTableHookDispatcher(
      DatastratoTableDispatcher dispatcher,
      Supplier<OwnerDispatcher> ownerDispatcher,
      CatalogManager catalogManager) {
    super(dispatcher, ownerDispatcher, catalogManager);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }

  @Override
  public Map<String, Object>[] preview(
      NameIdentifier identifier, Entity.EntityType type, int resultLimit, Column[] columns)
      throws DataPreviewSensitiveTableException {
    return dispatcher.preview(identifier, type, resultLimit, columns);
  }
}
