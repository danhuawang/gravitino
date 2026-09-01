/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestScimRepositoryPagination {

  @Test
  void testUnspecifiedCountUsesDefaultPageSize() {
    ScimRepositoryPagination.PageBounds page = ScimRepositoryPagination.normalizePage(1, null);
    assertEquals(0, page.offset());
    assertEquals(ScimRepositoryPagination.MAX_PAGE_SIZE, page.limit());
    assertEquals(1, page.startIndex());
  }

  @Test
  void testZeroCountReturnsEmptyPage() {
    ScimRepositoryPagination.PageBounds page = ScimRepositoryPagination.normalizePage(1, 0);
    assertEquals(0, page.offset());
    assertEquals(0, page.limit());
    assertEquals(1, page.startIndex());
  }

  @Test
  void testNegativeCountTreatedAsZero() {
    ScimRepositoryPagination.PageBounds page = ScimRepositoryPagination.normalizePage(1, -5);
    assertEquals(0, page.offset());
    assertEquals(0, page.limit());
    assertEquals(1, page.startIndex());
  }

  @Test
  void testPositiveCountCappedAtMax() {
    ScimRepositoryPagination.PageBounds page = ScimRepositoryPagination.normalizePage(11, 200);
    assertEquals(10, page.offset());
    assertEquals(ScimRepositoryPagination.MAX_PAGE_SIZE, page.limit());
    assertEquals(11, page.startIndex());
  }

  @Test
  void testStartIndexLessThanOneBecomesOne() {
    ScimRepositoryPagination.PageBounds page = ScimRepositoryPagination.normalizePage(0, 10);
    assertEquals(0, page.offset());
    assertEquals(10, page.limit());
    assertEquals(1, page.startIndex());
  }
}
