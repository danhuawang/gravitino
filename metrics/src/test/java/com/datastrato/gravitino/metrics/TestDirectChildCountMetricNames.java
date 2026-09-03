/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestDirectChildCountMetricNames {

  @Test
  void testNamesAreDeterministicBoundedAndScoped() {
    String catalog = DirectChildCountMetricNames.forCatalog("catalog");
    String schema = DirectChildCountMetricNames.forSchema("catalog", "parent:child");

    assertEquals(catalog, DirectChildCountMetricNames.forCatalog("catalog"));
    assertEquals(schema, DirectChildCountMetricNames.forSchema("catalog", "parent:child"));
    assertNotEquals(catalog, schema);
    assertNotEquals(schema, DirectChildCountMetricNames.forSchema("other", "parent:child"));
    assertTrue(catalog.length() <= 256);
    assertTrue(schema.length() <= 256);
    assertTrue(DirectChildCountMetricNames.isDirectChildCountMetric(catalog));
    assertTrue(DirectChildCountMetricNames.isDirectChildCountMetric(schema));
    assertFalse(DirectChildCountMetricNames.isDirectChildCountMetric("table_count"));
  }
}
