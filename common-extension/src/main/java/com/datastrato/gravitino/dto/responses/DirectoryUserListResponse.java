/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.DirectoryUserDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing Directory Users for the Configure → Directory page. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class DirectoryUserListResponse extends BaseResponse {

  @JsonProperty("users")
  private final DirectoryUserDTO[] users;

  /**
   * Creates a response with the given users.
   *
   * @param users Directory users.
   */
  public DirectoryUserListResponse(DirectoryUserDTO[] users) {
    super(0);
    this.users = users;
  }

  /** Jackson deserializer constructor. */
  public DirectoryUserListResponse() {
    super(0);
    this.users = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(users != null, "users must not be null");
  }
}
