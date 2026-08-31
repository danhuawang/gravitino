/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.meta.TopicEntity;

public interface DatastratoTopicDispatcher extends TopicDispatcher, EntityOperations<TopicEntity> {}
