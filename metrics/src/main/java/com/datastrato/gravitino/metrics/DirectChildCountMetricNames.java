/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import com.google.common.base.Preconditions;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;

/** Creates bounded, deterministic metric names for entity direct-child counts. */
public final class DirectChildCountMetricNames {
  private static final String PREFIX = "direct_child_count::";
  private static final String CATALOG_PREFIX = PREFIX + "catalog::";
  private static final String SCHEMA_PREFIX = PREFIX + "schema::";
  private static final char COMPONENT_SEPARATOR = '\u0000';

  private DirectChildCountMetricNames() {}

  /**
   * Returns the direct-child-count metric name for a catalog.
   *
   * @param catalogName catalog name
   * @return bounded metric name
   */
  public static String forCatalog(String catalogName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalogName), "Catalog name cannot be blank");
    return CATALOG_PREFIX + hash("catalog" + COMPONENT_SEPARATOR + catalogName);
  }

  /**
   * Returns the direct-child-count metric name for a schema.
   *
   * @param catalogName catalog name
   * @param schemaName full logical schema name
   * @return bounded metric name
   */
  public static String forSchema(String catalogName, String schemaName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalogName), "Catalog name cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(schemaName), "Schema name cannot be blank");
    return SCHEMA_PREFIX
        + hash(catalogName + COMPONENT_SEPARATOR + schemaName + COMPONENT_SEPARATOR + "schema");
  }

  /**
   * Returns whether a metric name represents an entity direct-child count.
   *
   * @param metricName metric name
   * @return whether this is a direct-child-count metric
   */
  public static boolean isDirectChildCountMetric(String metricName) {
    return metricName != null && metricName.startsWith(PREFIX);
  }

  private static String hash(String value) {
    return Hashing.sha256().hashString(value, StandardCharsets.UTF_8).toString();
  }
}
