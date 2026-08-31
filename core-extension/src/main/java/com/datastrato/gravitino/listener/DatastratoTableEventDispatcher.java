/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.listener.api.event.ListTableEvent;
import org.apache.gravitino.listener.api.event.ListTableFailureEvent;
import org.apache.gravitino.listener.api.event.ListTablePreEvent;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.utils.PrincipalUtils;

public class DatastratoTableEventDispatcher extends TableEventDispatcher
    implements DatastratoTableDispatcher {
  private final EventBus eventBus;
  private final DatastratoTableDispatcher dispatcher;
  /**
   * Constructs a TableEventDispatcher with a specified EventBus and TableCatalog.
   *
   * @param eventBus The EventBus to which events will be dispatched.
   * @param dispatcher The underlying {@link DatastratoTableDispatcher} that will perform the actual
   *     table operations.
   */
  public DatastratoTableEventDispatcher(EventBus eventBus, DatastratoTableDispatcher dispatcher) {
    super(eventBus, dispatcher);
    this.eventBus = eventBus;
    this.dispatcher = dispatcher;
  }

  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    eventBus.dispatchEvent(new ListTablePreEvent(PrincipalUtils.getCurrentUserName(), namespace));
    try {
      List<TableEntity> tableEntities = dispatcher.listEntities(namespace);
      int count = tableEntities == null ? -1 : tableEntities.size();
      eventBus.dispatchEvent(
          new ListTableEvent(PrincipalUtils.getCurrentUserName(), namespace, count));
      return tableEntities;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListTableFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }

  @Override
  public Map<String, Object>[] preview(
      NameIdentifier identifier, Entity.EntityType type, int resultLimit, Column[] columns)
      throws DataPreviewSensitiveTableException {
    // TODO: Add preview table event
    return dispatcher.preview(identifier, type, resultLimit, columns);
  }
}
