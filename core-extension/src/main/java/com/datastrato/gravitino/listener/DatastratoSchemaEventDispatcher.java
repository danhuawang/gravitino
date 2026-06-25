/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.SchemaEventDispatcher;
import org.apache.gravitino.listener.api.event.ListSchemaEvent;
import org.apache.gravitino.listener.api.event.ListSchemaFailureEvent;
import org.apache.gravitino.listener.api.event.ListSchemaPreEvent;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.utils.PrincipalUtils;

public class DatastratoSchemaEventDispatcher extends SchemaEventDispatcher
    implements DatastratoSchemaDispatcher {
  private final DatastratoSchemaDispatcher dispatcher;
  private final EventBus eventBus;

  /**
   * Constructs a SchemaEventDispatcher with a specified EventBus and SchemaDispatcher.
   *
   * @param eventBus The EventBus to which events will be dispatched.
   * @param dispatcher The underlying {@link DatastratoSchemaDispatcher} that will perform the
   *     actual schema operations.
   */
  public DatastratoSchemaEventDispatcher(EventBus eventBus, DatastratoSchemaDispatcher dispatcher) {
    super(eventBus, dispatcher);
    this.eventBus = eventBus;
    this.dispatcher = dispatcher;
  }

  @Override
  public List<SchemaEntity> listEntities(Namespace namespace) {
    eventBus.dispatchEvent(new ListSchemaPreEvent(PrincipalUtils.getCurrentUserName(), namespace));
    try {
      List<SchemaEntity> schemaEntities = dispatcher.listEntities(namespace);
      int count = schemaEntities == null ? -1 : schemaEntities.size();
      eventBus.dispatchEvent(
          new ListSchemaEvent(PrincipalUtils.getCurrentUserName(), namespace, count));
      return schemaEntities;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListSchemaFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }
}
