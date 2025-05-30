/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import com.datastrato.gravitino.preview.DataPreviewOperations;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.meta.TableEntity;

public interface DatastratoTableDispatcher
    extends TableDispatcher, EntityOperations<TableEntity>, DataPreviewOperations {}
