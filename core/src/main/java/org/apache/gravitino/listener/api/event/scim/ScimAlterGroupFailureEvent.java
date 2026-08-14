/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered when a SCIM alter Group operation fails. */
@DeveloperApi
public class ScimAlterGroupFailureEvent extends ScimGroupFailureEvent {

  private final String groupName;

  /**
   * Creates a SCIM alter Group failure event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param exception failure cause
   * @param groupName group name when known
   * @param resourceId SCIM / Gravitino id when known; may be null
   * @param externalId SCIM externalId; may be null
   */
  public ScimAlterGroupFailureEvent(
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
    return OperationType.ALTER_GROUP;
  }
}
