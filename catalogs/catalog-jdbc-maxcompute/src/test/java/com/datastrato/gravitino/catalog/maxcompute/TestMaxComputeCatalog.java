/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for MaxComputeCatalog. */
class TestMaxComputeCatalog {

  @Test
  void testShortName() {
    MaxComputeCatalog catalog = new MaxComputeCatalog();
    assertEquals("jdbc-maxcompute", catalog.shortName());
  }

  @Test
  void testNewCapability() {
    MaxComputeCatalog catalog = new MaxComputeCatalog();
    assertNotNull(catalog.newCapability());
    assertTrue(catalog.newCapability() instanceof MaxComputeCatalogCapability);
  }
}
