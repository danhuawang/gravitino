/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.hook.TopicHookDispatcher;
import org.apache.gravitino.meta.TopicEntity;

public class DatastratoTopicHookDispatcher extends TopicHookDispatcher
    implements DatastratoTopicDispatcher {
  private final DatastratoTopicDispatcher dispatcher;

  public DatastratoTopicHookDispatcher(DatastratoTopicDispatcher dispatcher) {
    super(dispatcher);
    this.dispatcher = dispatcher;
  }

  @Override
  public List<TopicEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }
}
