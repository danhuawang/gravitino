/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.model;

import java.util.Collections;
import java.util.List;

/** SCIM list/filter query result for repository adapters. */
public final class ScimPagedResult<T> {

  private final long totalCount;
  private final List<T> items;

  /**
   * Creates a paginated SCIM query result.
   *
   * @param totalCount The total number of matching items.
   * @param items The items in the current page.
   */
  public ScimPagedResult(long totalCount, List<T> items) {
    this.totalCount = totalCount;
    this.items = items != null ? items : Collections.emptyList();
  }

  /**
   * Returns the total number of matching items.
   *
   * @return The total count.
   */
  public long totalCount() {
    return totalCount;
  }

  /**
   * Returns the items in the current page.
   *
   * @return The page items.
   */
  public List<T> items() {
    return items;
  }
}
