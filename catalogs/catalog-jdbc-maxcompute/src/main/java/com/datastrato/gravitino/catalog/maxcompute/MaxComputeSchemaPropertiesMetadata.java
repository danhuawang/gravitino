/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcSchemaPropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/** Schema properties metadata for MaxCompute. */
public class MaxComputeSchemaPropertiesMetadata extends JdbcSchemaPropertiesMetadata {

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return ImmutableMap.of();
  }
}
