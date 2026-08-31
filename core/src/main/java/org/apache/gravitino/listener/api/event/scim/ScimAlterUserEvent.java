/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered after a successful SCIM alter User operation. */
@DeveloperApi
public class ScimAlterUserEvent extends ScimUserEvent {

  private final String userName;

  /**
   * Creates a SCIM alter User success event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param userName SCIM userName
   * @param resourceId SCIM / Gravitino id
   * @param externalId SCIM externalId; may be null
   */
  public ScimAlterUserEvent(
      String initiator,
      String metalake,
      String userName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    this(initiator, metalake, userName, resourceId, externalId, null);
  }

  /**
   * Creates a SCIM alter User success event with optional change details.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param userName SCIM userName
   * @param resourceId SCIM / Gravitino id
   * @param externalId SCIM externalId; may be null
   * @param extraInfo optional customInfo extras (for example {@code changes})
   */
  public ScimAlterUserEvent(
      String initiator,
      String metalake,
      String userName,
      @Nullable String resourceId,
      @Nullable String externalId,
      @Nullable Map<String, String> extraInfo) {
    super(
        initiator,
        NameIdentifierUtil.ofUser(metalake, userName),
        resourceId,
        externalId,
        extraInfo);
    this.userName = userName;
  }

  /** Returns the user name. */
  public String userName() {
    return userName;
  }

  @Override
  public OperationType operationType() {
    return OperationType.ALTER_USER;
  }
}
