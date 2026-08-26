/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.listener.api.event.scim;

import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered when a SCIM list/find Users operation fails. */
@DeveloperApi
public class ScimListUsersFailureEvent extends ScimUserFailureEvent {

  private final int startIndex;
  private final int count;

  /**
   * Creates a SCIM list Users failure event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param exception failure cause
   * @param startIndex SCIM 1-based startIndex; 0 when unset
   * @param count requested page size; 0 when unset
   */
  public ScimListUsersFailureEvent(
      String initiator, String metalake, Exception exception, int startIndex, int count) {
    super(initiator, NameIdentifierUtil.ofMetalake(metalake), exception, null, null);
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
    return OperationType.LIST_USERS_PAGED;
  }
}
