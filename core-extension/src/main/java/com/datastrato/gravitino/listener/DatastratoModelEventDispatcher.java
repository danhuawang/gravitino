/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.ModelEventDispatcher;
import org.apache.gravitino.listener.api.event.ListModelEvent;
import org.apache.gravitino.listener.api.event.ListModelFailureEvent;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.utils.PrincipalUtils;

public class DatastratoModelEventDispatcher extends ModelEventDispatcher
    implements DatastratoModelDispatcher {

  private final DatastratoModelDispatcher dispatcher;
  private final EventBus eventBus;

  public DatastratoModelEventDispatcher(EventBus eventBus, DatastratoModelDispatcher dispatcher) {
    super(eventBus, dispatcher);
    this.dispatcher = dispatcher;
    this.eventBus = eventBus;
  }

  @Override
  public List<ModelEntity> listEntities(Namespace namespace) {
    try {
      List<ModelEntity> modelEntities = dispatcher.listEntities(namespace);
      eventBus.dispatchEvent(new ListModelEvent(PrincipalUtils.getCurrentUserName(), namespace));
      return ImmutableList.copyOf(modelEntities);
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListModelFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }
}
