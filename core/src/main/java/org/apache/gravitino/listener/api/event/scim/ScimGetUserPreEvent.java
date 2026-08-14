/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.listener.api.event.scim;

import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM get User operation. */
@DeveloperApi
public class ScimGetUserPreEvent extends ScimUserPreEvent {

  /**
   * Creates a SCIM get User pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param resourceId SCIM / Gravitino id being retrieved
   */
  public ScimGetUserPreEvent(String initiator, String metalake, String resourceId) {
    super(initiator, identifierFor(metalake, resourceId), resourceId, null);
  }

  @Override
  public OperationType operationType() {
    return OperationType.GET_USER_BY_ID;
  }

  private static NameIdentifier identifierFor(String metalake, String resourceId) {
    try {
      return AuthorizationUtils.ofUserId(metalake, Long.parseLong(resourceId));
    } catch (NumberFormatException e) {
      return NameIdentifierUtil.ofMetalake(metalake);
    }
  }
}
