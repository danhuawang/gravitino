/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.listener.api.event.scim;

import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered when a SCIM get User operation fails. */
@DeveloperApi
public class ScimGetUserFailureEvent extends ScimUserFailureEvent {

  /**
   * Creates a SCIM get User failure event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param exception failure cause
   * @param resourceId SCIM / Gravitino id being retrieved; may be null
   */
  public ScimGetUserFailureEvent(
      String initiator, String metalake, Exception exception, @Nullable String resourceId) {
    super(initiator, identifierFor(metalake, resourceId), exception, resourceId, null);
  }

  @Override
  public OperationType operationType() {
    return OperationType.GET_USER_BY_ID;
  }

  private static NameIdentifier identifierFor(String metalake, String resourceId) {
    if (resourceId == null) {
      return NameIdentifierUtil.ofMetalake(metalake);
    }
    try {
      return AuthorizationUtils.ofUserId(metalake, Long.parseLong(resourceId));
    } catch (NumberFormatException e) {
      return NameIdentifierUtil.ofMetalake(metalake);
    }
  }
}
