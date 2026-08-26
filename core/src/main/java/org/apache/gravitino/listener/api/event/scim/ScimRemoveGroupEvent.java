/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered after a successful SCIM remove Group operation. */
@DeveloperApi
public class ScimRemoveGroupEvent extends ScimGroupEvent {

  private final String groupName;

  /**
   * Creates a SCIM remove Group success event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param groupName SCIM displayName (group name)
   * @param resourceId SCIM / Gravitino id
   * @param externalId SCIM externalId; may be null
   */
  public ScimRemoveGroupEvent(
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
