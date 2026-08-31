/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM remove User operation. */
@DeveloperApi
public class ScimRemoveUserPreEvent extends ScimUserPreEvent {

  private final String userName;

  /**
   * Creates a SCIM remove User pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param userName user name
   * @param resourceId SCIM / Gravitino id when already known; may be null on create
   * @param externalId SCIM externalId; may be null
   */
  public ScimRemoveUserPreEvent(
      String initiator,
      String metalake,
      String userName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(initiator, NameIdentifierUtil.ofUser(metalake, userName), resourceId, externalId);
    this.userName = userName;
  }

  /** Returns the user name. */
  public String userName() {
    return userName;
  }

  @Override
  public OperationType operationType() {
    return OperationType.REMOVE_USER;
  }
}
