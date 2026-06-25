/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.ViewEventDispatcher;
import org.apache.gravitino.listener.api.event.view.ListViewEvent;
import org.apache.gravitino.listener.api.event.view.ListViewFailureEvent;
import org.apache.gravitino.listener.api.event.view.ListViewPreEvent;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.utils.PrincipalUtils;

public class DatastratoViewEventDispatcher extends ViewEventDispatcher
    implements DatastratoViewDispatcher {
  private final DatastratoViewDispatcher dispatcher;
  private final EventBus eventBus;

  /**
   * Constructs a ViewEventDispatcher with a specified EventBus and ViewDispatcher.
   *
   * @param eventBus The EventBus to which events will be dispatched.
   * @param dispatcher The underlying {@link DatastratoViewDispatcher} that will perform the actual
   *     view operations.
   */
  public DatastratoViewEventDispatcher(EventBus eventBus, DatastratoViewDispatcher dispatcher) {
    super(eventBus, dispatcher);
    this.dispatcher = dispatcher;
    this.eventBus = eventBus;
  }

  @Override
  public List<ViewEntity> listEntities(Namespace namespace) {
    eventBus.dispatchEvent(new ListViewPreEvent(PrincipalUtils.getCurrentUserName(), namespace));
    try {
      List<ViewEntity> viewEntities = dispatcher.listEntities(namespace);
      int count = viewEntities == null ? -1 : viewEntities.size();
      eventBus.dispatchEvent(
          new ListViewEvent(PrincipalUtils.getCurrentUserName(), namespace, count));
      return viewEntities;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListViewFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }
}
