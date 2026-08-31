/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.meta.ViewEntity;

public interface DatastratoViewDispatcher extends ViewDispatcher, EntityOperations<ViewEntity> {}
