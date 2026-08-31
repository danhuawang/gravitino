/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM remove Group operation. */
@DeveloperApi
public class ScimRemoveGroupPreEvent extends ScimGroupPreEvent {

  private final String groupName;

  /**
   * Creates a SCIM remove Group pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param groupName group name
   * @param resourceId SCIM / Gravitino id when already known; may be null on create
   * @param externalId SCIM externalId; may be null
   */
  public ScimRemoveGroupPreEvent(
      String initiator,
      String metalake,
      String groupName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(initiator, NameIdentifierUtil.ofGroup(metalake, groupName), resourceId, externalId);
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
