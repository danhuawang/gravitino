/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import com.google.common.collect.ImmutableMap;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/**
 * Event triggered after a successful SCIM list/find Groups operation.
 *
 * <p>Does not implement {@code ListEvent}: page size is carried in {@code customInfo} as {@code
 * count} so audit formatters do not append a second count outside the map.
 */
@DeveloperApi
public class ScimListGroupsEvent extends ScimGroupEvent {

  private final int startIndex;
  private final int count;
  private final int pageSize;
  private final long totalCount;

  /**
   * Creates a SCIM list Groups success event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param startIndex SCIM 1-based startIndex; 0 when unset
   * @param count requested page size; 0 when unset
   * @param pageSize number of groups returned in this page
   * @param totalCount total matching groups
   */
  public ScimListGroupsEvent(
      String initiator, String metalake, int startIndex, int count, int pageSize, long totalCount) {
    super(
        initiator,
        NameIdentifierUtil.ofMetalake(metalake),
        null,
        null,
        ImmutableMap.of(ScimAuditInfos.INFO_COUNT, String.valueOf(pageSize)));
    this.startIndex = startIndex;
    this.count = count;
    this.pageSize = pageSize;
    this.totalCount = totalCount;
  }

  /** Returns the SCIM startIndex. */
  public int startIndex() {
    return startIndex;
  }

  /** Returns the requested page size. */
  public int count() {
    return count;
  }

  /** Returns the number of groups returned in this page. */
  public int pageSize() {
    return pageSize;
  }

  /** Returns the total matching groups. */
  public long totalCount() {
    return totalCount;
  }

  @Override
  public OperationType operationType() {
    return OperationType.LIST_GROUPS_PAGED;
  }
}
