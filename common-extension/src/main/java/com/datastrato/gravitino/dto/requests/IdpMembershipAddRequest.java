/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Request to add Local IdP users to Local IdP groups ({@code idp_user_group_rel}).
 *
 * <p>Builds the cartesian product of {@code usernames} × {@code groupNames}. Existing active
 * memberships are skipped and do not fail the request.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class IdpMembershipAddRequest implements RESTRequest {

  private static final int MAX_NAME_LENGTH = 128;

  @JsonProperty("usernames")
  private List<String> usernames;

  @JsonProperty("groupNames")
  private List<String> groupNames;

  /** Default constructor for Jackson deserialization. */
  private IdpMembershipAddRequest() {
    this(null, null);
  }

  /**
   * Creates a Local IdP membership add request.
   *
   * @param usernames Local IdP usernames.
   * @param groupNames Local IdP group names.
   */
  public IdpMembershipAddRequest(List<String> usernames, List<String> groupNames) {
    this.usernames = usernames;
    this.groupNames = groupNames;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        usernames != null && !usernames.isEmpty(),
        "\"usernames\" field is required and cannot be empty");
    Preconditions.checkArgument(
        groupNames != null && !groupNames.isEmpty(),
        "\"groupNames\" field is required and cannot be empty");
    for (String username : usernames) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(username), "username in \"usernames\" cannot be blank");
      Preconditions.checkArgument(!username.contains(":"), "User name cannot contain a colon (:)");
      Preconditions.checkArgument(
          username.length() <= MAX_NAME_LENGTH,
          "Username must not exceed %s characters",
          MAX_NAME_LENGTH);
    }
    for (String groupName : groupNames) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(groupName), "group name in \"groupNames\" cannot be blank");
      Preconditions.checkArgument(
          groupName.length() <= MAX_NAME_LENGTH,
          "Group name must not exceed %s characters",
          MAX_NAME_LENGTH);
    }
  }
}
