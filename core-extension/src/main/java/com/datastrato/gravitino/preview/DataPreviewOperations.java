/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.preview;

import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;

/** The data preview operations for the relational metadata object. */
public interface DataPreviewOperations {

  /**
   * The data preview operation for the relational metadata object.
   *
   * @param identifier The nameIdentifier for the relational metadata object.
   * @param type The entity type for the relational metadata object.
   * @param resultLimit The row limit of the data.
   * @return The data which can be previewed.
   * @throws DataPreviewSensitiveTableException If the relational metadata object is sensitive, it
   *     won't return any data, it will throw a PreviewSensitiveTableException.
   */
  Map<String, Object>[] preview(NameIdentifier identifier, Entity.EntityType type, int resultLimit)
      throws DataPreviewSensitiveTableException;
}
