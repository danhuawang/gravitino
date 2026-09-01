/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service;

import com.datastrato.gravitino.scim.v2.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.v2.model.ScimUserMeta;

/** Test entity builders for SCIM service unit tests. */
public final class ScimServiceTestEntities {

  private ScimServiceTestEntities() {}

  public static ScimUserMeta user(
      long userId, String userName, String externalId, boolean enabled) {
    return ScimUserMeta.builder()
        .withUserId(userId)
        .withUserName(userName)
        .withExternalId(externalId)
        .withEnabled(enabled)
        .build();
  }

  public static ScimGroupMeta group(long groupId, String groupName, String externalId) {
    return ScimGroupMeta.builder()
        .withGroupId(groupId)
        .withGroupName(groupName)
        .withGroupComment("")
        .withExternalId(externalId)
        .build();
  }
}
