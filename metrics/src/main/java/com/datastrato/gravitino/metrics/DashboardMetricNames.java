/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;

/** Builds and parses structured Dashboard metric names. */
final class DashboardMetricNames {
  static final int MAX_METRIC_NAME_LENGTH = 256;
  static final String SEPARATOR = "::";

  private static final String BY_CATALOG_SCOPE = "by_catalog";

  private DashboardMetricNames() {}

  static String forCatalog(String catalogName, String provider, String metricName) {
    validateComponent("catalog name", catalogName);
    validateComponent("provider", provider);
    validateComponent("metric name", metricName);

    String name = String.join(SEPARATOR, BY_CATALOG_SCOPE, catalogName, provider, metricName);
    Preconditions.checkArgument(
        name.length() <= MAX_METRIC_NAME_LENGTH,
        "Dashboard metric name exceeds the %s-character storage limit: %s",
        MAX_METRIC_NAME_LENGTH,
        name);
    return name;
  }

  static CatalogMetric parseCatalog(String name) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "Dashboard metric name is required");
    Preconditions.checkArgument(
        name.length() <= MAX_METRIC_NAME_LENGTH,
        "Dashboard metric name exceeds the %s-character storage limit: %s",
        MAX_METRIC_NAME_LENGTH,
        name);

    String[] components = name.split(SEPARATOR, -1);
    Preconditions.checkArgument(
        components.length == 4 && BY_CATALOG_SCOPE.equals(components[0]),
        "Invalid by-catalog Dashboard metric name: %s",
        name);
    validateComponent("catalog name", components[1]);
    validateComponent("provider", components[2]);
    validateComponent("metric name", components[3]);
    return new CatalogMetric(components[1], components[2], components[3]);
  }

  private static void validateComponent(String componentName, String value) {
    Preconditions.checkArgument(StringUtils.isNotBlank(value), "%s is required", componentName);
    Preconditions.checkArgument(
        !value.contains(SEPARATOR),
        "%s must not contain the Dashboard metric separator %s: %s",
        componentName,
        SEPARATOR,
        value);
  }

  static final class CatalogMetric {
    private final String catalogName;
    private final String provider;
    private final String metricName;

    private CatalogMetric(String catalogName, String provider, String metricName) {
      this.catalogName = catalogName;
      this.provider = provider;
      this.metricName = metricName;
    }

    String catalogName() {
      return catalogName;
    }

    String provider() {
      return provider;
    }

    String metricName() {
      return metricName;
    }
  }
}
