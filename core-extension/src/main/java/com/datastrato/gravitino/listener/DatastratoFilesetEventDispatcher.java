/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.FilesetEventDispatcher;
import org.apache.gravitino.listener.api.event.ListFilesetEvent;
import org.apache.gravitino.listener.api.event.ListFilesetFailureEvent;
import org.apache.gravitino.listener.api.event.ListFilesetPreEvent;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.utils.PrincipalUtils;

public class DatastratoFilesetEventDispatcher extends FilesetEventDispatcher
    implements DatastratoFilesetDispatcher {
  private final EventBus eventBus;
  private final DatastratoFilesetDispatcher dispatcher;

  public DatastratoFilesetEventDispatcher(
      EventBus eventBus, DatastratoFilesetDispatcher dispatcher) {
    super(eventBus, dispatcher);
    this.eventBus = eventBus;
    this.dispatcher = dispatcher;
  }

  @Override
  public List<FilesetEntity> listEntities(Namespace namespace) {
    eventBus.dispatchEvent(new ListFilesetPreEvent(PrincipalUtils.getCurrentUserName(), namespace));
    try {
      List<FilesetEntity> filesetEntities = dispatcher.listEntities(namespace);
      int count = filesetEntities == null ? -1 : filesetEntities.size();
      eventBus.dispatchEvent(
          new ListFilesetEvent(PrincipalUtils.getCurrentUserName(), namespace, count));
      return filesetEntities;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListFilesetFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }
}
