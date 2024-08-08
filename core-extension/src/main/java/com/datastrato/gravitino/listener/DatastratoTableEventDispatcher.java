/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.listener.api.event.ListTableEvent;
import org.apache.gravitino.listener.api.event.ListTableFailureEvent;
import org.apache.gravitino.meta.TableEntity;
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
    try {
      List<TableEntity> tableEntities = dispatcher.listEntities(namespace);
      eventBus.dispatchEvent(new ListTableEvent(PrincipalUtils.getCurrentUserName(), namespace));
      return tableEntities;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListTableFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }
}
