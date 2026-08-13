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
   * Normalizes SCIM pagination parameters per RFC 7644 Section 3.4.2.4.
   *
   * <ul>
   *   <li>{@code startIndex} less than 1 is treated as 1
   *   <li>{@code count} unspecified uses {@link #MAX_PAGE_SIZE}
   *   <li>{@code count} zero or negative is treated as 0 (return no resources, only {@code
   *       totalResults})
   *   <li>positive {@code count} is capped at {@link #MAX_PAGE_SIZE}
   * </ul>
   *
   * @param startIndex SCIM start index (1-based)
   * @param count requested page size
   * @return normalized offset (0-based) and limit
   */
  public static PageBounds normalizePage(Integer startIndex, Integer count) {
    int normalizedStartIndex = startIndex == null || startIndex < 1 ? 1 : startIndex;
    int limit = normalizeCount(count);
    int offset = normalizedStartIndex - 1;
    return new PageBounds(offset, limit, normalizedStartIndex);
  }

  /**
   * Normalizes the SCIM {@code count} query parameter.
   *
   * @param count requested page size, or {@code null} when omitted
   * @return page size to apply (0 means return no resources)
   */
  private static int normalizeCount(Integer count) {
    if (count == null) {
      return MAX_PAGE_SIZE;
    }
    if (count < 1) {
      // RFC 7644: negative count SHALL be interpreted as 0; 0 returns no Resources.
      return 0;
    }
    return Math.min(count, MAX_PAGE_SIZE);
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
