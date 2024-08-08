/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.meta.SchemaEntity;

public interface DatastratoSchemaDispatcher
    extends SchemaDispatcher, EntityOperations<SchemaEntity> {}
