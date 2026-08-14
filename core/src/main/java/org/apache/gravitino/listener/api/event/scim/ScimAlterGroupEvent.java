/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered after a successful SCIM alter Group operation. */
@DeveloperApi
public class ScimAlterGroupEvent extends ScimGroupEvent {

  private final String groupName;

  /**
   * Creates a SCIM alter Group success event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param groupName SCIM displayName (group name)
   * @param resourceId SCIM / Gravitino id
   * @param externalId SCIM externalId; may be null
   */
  public ScimAlterGroupEvent(
      String initiator,
      String metalake,
      String groupName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    this(initiator, metalake, groupName, resourceId, externalId, null);
  }

  /**
   * Creates a SCIM alter Group success event with optional membership / change details.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param groupName SCIM displayName (group name)
   * @param resourceId SCIM / Gravitino id
   * @param externalId SCIM externalId; may be null
   * @param extraInfo optional customInfo extras ({@code membersAdded}, {@code membersRemoved},
   *     {@code changes})
   */
  public ScimAlterGroupEvent(
      String initiator,
      String metalake,
      String groupName,
      @Nullable String resourceId,
      @Nullable String externalId,
      @Nullable Map<String, String> extraInfo) {
    super(
        initiator,
        NameIdentifierUtil.ofGroup(metalake, groupName),
        resourceId,
        externalId,
        extraInfo);
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
