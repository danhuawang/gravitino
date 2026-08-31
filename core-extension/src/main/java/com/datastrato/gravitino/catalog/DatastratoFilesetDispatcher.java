/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.meta.FilesetEntity;

public interface DatastratoFilesetDispatcher
    extends FilesetDispatcher, EntityOperations<FilesetEntity> {}
