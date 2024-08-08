/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.meta.FilesetEntity;

public interface DatastratoFilesetDispatcher
    extends FilesetDispatcher, EntityOperations<FilesetEntity> {}
