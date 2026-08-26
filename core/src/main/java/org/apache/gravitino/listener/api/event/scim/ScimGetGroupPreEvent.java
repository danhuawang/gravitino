/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.listener.api.event.scim;

import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM get Group operation. */
@DeveloperApi
public class ScimGetGroupPreEvent extends ScimGroupPreEvent {

  /**
   * Creates a SCIM get Group pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param resourceId SCIM / Gravitino id being retrieved
   */
  public ScimGetGroupPreEvent(String initiator, String metalake, String resourceId) {
    super(initiator, identifierFor(metalake, resourceId), resourceId, null);
  }

  @Override
  public OperationType operationType() {
    return OperationType.GET_GROUP_BY_ID;
  }

  private static NameIdentifier identifierFor(String metalake, String resourceId) {
    try {
      return AuthorizationUtils.ofGroupId(metalake, Long.parseLong(resourceId));
    } catch (NumberFormatException e) {
      return NameIdentifierUtil.ofMetalake(metalake);
    }
  }
}
