/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.TopicEventDispatcher;
import org.apache.gravitino.listener.api.event.ListTopicEvent;
import org.apache.gravitino.listener.api.event.ListTopicFailureEvent;
import org.apache.gravitino.listener.api.event.ListTopicPreEvent;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.utils.PrincipalUtils;

public class DatastratoTopicEventDispatcher extends TopicEventDispatcher
    implements DatastratoTopicDispatcher {

  private final EventBus eventBus;
  private final DatastratoTopicDispatcher dispatcher;

  /**
   * Constructs a TopicEventDispatcher with a specified EventBus and TopicCatalog.
   *
   * @param eventBus The EventBus to which events will be dispatched.
   * @param dispatcher The underlying {@link DatastratoTopicDispatcher} that will perform the actual
   *     topic operations.
   */
  public DatastratoTopicEventDispatcher(EventBus eventBus, DatastratoTopicDispatcher dispatcher) {
    super(eventBus, dispatcher);
    this.eventBus = eventBus;
    this.dispatcher = dispatcher;
  }

  @Override
  public List<TopicEntity> listEntities(Namespace namespace) {
    eventBus.dispatchEvent(new ListTopicPreEvent(PrincipalUtils.getCurrentUserName(), namespace));
    try {
      List<TopicEntity> topicEntities = dispatcher.listEntities(namespace);
      int count = topicEntities == null ? -1 : topicEntities.size();
      eventBus.dispatchEvent(
          new ListTopicEvent(PrincipalUtils.getCurrentUserName(), namespace, count));
      return topicEntities;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ListTopicFailureEvent(PrincipalUtils.getCurrentUserName(), namespace, e));
      throw e;
    }
  }
}
