/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/** Request to batch update the {@code enabled} flag for metalake users. */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class UserEnabledBatchUpdateRequest implements RESTRequest {

  @JsonProperty("users")
  private String[] users;

  @JsonProperty("enabled")
  private Boolean enabled;

  /** Default constructor for Jackson deserialization. */
  private UserEnabledBatchUpdateRequest() {
    this(null, null);
  }

  /**
   * Creates a new batch update request.
   *
   * @param users User names to update.
   * @param enabled Target enabled flag.
   */
  public UserEnabledBatchUpdateRequest(String[] users, Boolean enabled) {
    this.users = users;
    this.enabled = enabled;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        users != null && users.length > 0, "\"users\" field is required and cannot be empty");
    for (String user : users) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(user), "user name in \"users\" cannot be blank");
    }
    Preconditions.checkArgument(
        enabled != null, "\"enabled\" field is required and cannot be null");
  }
}
