/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.meta.ModelEntity;

public interface DatastratoModelDispatcher extends ModelDispatcher, EntityOperations<ModelEntity> {}
