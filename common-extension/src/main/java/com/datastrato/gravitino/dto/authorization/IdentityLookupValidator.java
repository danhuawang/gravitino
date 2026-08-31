/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;

/** Validates path and query parameters for identity lookup APIs. */
public final class IdentityLookupValidator {

  private static final int MAX_USERNAME_LENGTH = 128;
  private static final int MAX_GROUP_NAME_LENGTH = 128;

  private IdentityLookupValidator() {}

  /**
   * Validates parameters for looking up a user's groups.
   *
   * @param username The username from the path.
   * @param type The identity type from the query string.
   */
  public static void validateUserGroupsLookup(String username, IdentityType type) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(username), "\"username\" field is required and cannot be empty");
    Preconditions.checkArgument(!username.contains(":"), "Username cannot contain a colon (:)");
    Preconditions.checkArgument(
        username.length() <= MAX_USERNAME_LENGTH,
        "Username must not exceed %s characters",
        MAX_USERNAME_LENGTH);
    Preconditions.checkArgument(type != null, "\"type\" field is required");
  }

  /**
   * Validates parameters for looking up group metadata.
   *
   * @param groupName The group name from the path.
   * @param type The identity type from the query string.
   */
  public static void validateGroupLookup(String groupName, IdentityType type) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(groupName), "\"groupName\" field is required and cannot be empty");
    Preconditions.checkArgument(
        groupName.length() <= MAX_GROUP_NAME_LENGTH,
        "Group name must not exceed %s characters",
        MAX_GROUP_NAME_LENGTH);
    Preconditions.checkArgument(type != null, "\"type\" field is required");
  }
}
