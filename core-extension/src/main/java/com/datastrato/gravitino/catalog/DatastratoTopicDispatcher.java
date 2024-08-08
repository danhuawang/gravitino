/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.meta.TopicEntity;

public interface DatastratoTopicDispatcher extends TopicDispatcher, EntityOperations<TopicEntity> {}
