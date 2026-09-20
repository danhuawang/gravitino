/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
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

  @Test
  void testWithCatalogConfReturnsSameCatalog() {
    // withCatalogConf must remain fluent so the timeout injection does not break the builder chain;
    // the injection behaviour itself is covered by TestMaxComputeTimeoutUtils.
    MaxComputeCatalog catalog = new MaxComputeCatalog();
    Map<String, String> conf = new HashMap<>();
    conf.put(
        "jdbc-url", "jdbc:odps:http://service.cn-hangzhou.maxcompute.aliyun.com/api?project=p");

    assertSame(catalog, catalog.withCatalogConf(conf));
  }
}
