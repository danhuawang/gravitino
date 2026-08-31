/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import com.datastrato.gravitino.preview.DataPreviewOperations;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.meta.TableEntity;

public interface DatastratoTableDispatcher
    extends TableDispatcher, EntityOperations<TableEntity>, DataPreviewOperations {}
