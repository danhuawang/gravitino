/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered when a SCIM add User operation fails. */
@DeveloperApi
public class ScimAddUserFailureEvent extends ScimUserFailureEvent {

  private final String userName;

  /**
   * Creates a SCIM add User failure event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param exception failure cause
   * @param userName user name when known
   * @param resourceId SCIM / Gravitino id when known; may be null
   * @param externalId SCIM externalId; may be null
   */
  public ScimAddUserFailureEvent(
      String initiator,
      String metalake,
      Exception exception,
      String userName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(
        initiator,
        NameIdentifierUtil.ofUser(metalake, userName),
        exception,
        resourceId,
        externalId);
    this.userName = userName;
  }

  /** Returns the user name. */
  public String userName() {
    return userName;
  }

  @Override
  public OperationType operationType() {
    return OperationType.ADD_USER;
  }
}
