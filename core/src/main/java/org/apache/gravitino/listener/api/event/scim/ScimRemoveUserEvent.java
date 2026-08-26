/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered after a successful SCIM remove User operation. */
@DeveloperApi
public class ScimRemoveUserEvent extends ScimUserEvent {

  private final String userName;

  /**
   * Creates a SCIM remove User success event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param userName SCIM userName
   * @param resourceId SCIM / Gravitino id
   * @param externalId SCIM externalId; may be null
   */
  public ScimRemoveUserEvent(
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
