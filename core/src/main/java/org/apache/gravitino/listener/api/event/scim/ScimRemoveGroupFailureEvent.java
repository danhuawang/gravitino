/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered when a SCIM remove Group operation fails. */
@DeveloperApi
public class ScimRemoveGroupFailureEvent extends ScimGroupFailureEvent {

  private final String groupName;

  /**
   * Creates a SCIM remove Group failure event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param exception failure cause
   * @param groupName group name when known
   * @param resourceId SCIM / Gravitino id when known; may be null
   * @param externalId SCIM externalId; may be null
   */
  public ScimRemoveGroupFailureEvent(
      String initiator,
      String metalake,
      Exception exception,
      String groupName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(
        initiator,
        NameIdentifierUtil.ofGroup(metalake, groupName),
        exception,
        resourceId,
        externalId);
    this.groupName = groupName;
  }

  /** Returns the group name. */
  public String groupName() {
    return groupName;
  }

  @Override
  public OperationType operationType() {
    return OperationType.REMOVE_GROUP;
  }
}
