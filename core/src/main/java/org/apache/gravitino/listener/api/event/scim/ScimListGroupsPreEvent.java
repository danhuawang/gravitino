/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM list/find Groups operation. */
@DeveloperApi
public class ScimListGroupsPreEvent extends ScimGroupPreEvent {

  private final int startIndex;
  private final int count;

  /**
   * Creates a SCIM list Groups pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param startIndex SCIM 1-based startIndex; 0 when unset
   * @param count requested page size; 0 when unset
   */
  public ScimListGroupsPreEvent(String initiator, String metalake, int startIndex, int count) {
    super(initiator, NameIdentifierUtil.ofMetalake(metalake), null, null);
    this.startIndex = startIndex;
    this.count = count;
  }

  /** Returns the SCIM startIndex. */
  public int startIndex() {
    return startIndex;
  }

  /** Returns the requested page size. */
  public int count() {
    return count;
  }

  @Override
  public OperationType operationType() {
    return OperationType.LIST_GROUPS_PAGED;
  }
}
