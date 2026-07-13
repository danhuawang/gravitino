/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

/** SCIM pagination helpers for repository adapters. */
public final class ScimRepositoryPagination {

  /** Maximum SCIM page size per design §8.5. */
  public static final int MAX_PAGE_SIZE = 100;

  private ScimRepositoryPagination() {}

  /**
   * Normalizes SCIM pagination parameters.
   *
   * @param startIndex SCIM start index (1-based)
   * @param count requested page size
   * @return normalized offset (0-based) and limit
   */
  public static PageBounds normalizePage(Integer startIndex, Integer count) {
    int normalizedStartIndex = startIndex == null || startIndex < 1 ? 1 : startIndex;
    int limit = count == null || count < 1 ? MAX_PAGE_SIZE : Math.min(count, MAX_PAGE_SIZE);
    int offset = normalizedStartIndex - 1;
    return new PageBounds(offset, limit, normalizedStartIndex);
  }

  /** Normalized SCIM pagination bounds. */
  public static final class PageBounds {
    private final int offset;
    private final int limit;
    private final int startIndex;

    private PageBounds(int offset, int limit, int startIndex) {
      this.offset = offset;
      this.limit = limit;
      this.startIndex = startIndex;
    }

    /** Returns JDBC offset. */
    public int offset() {
      return offset;
    }

    /** Returns JDBC limit. */
    public int limit() {
      return limit;
    }

    /** Returns SCIM start index for the response page. */
    public int startIndex() {
      return startIndex;
    }
  }
}
