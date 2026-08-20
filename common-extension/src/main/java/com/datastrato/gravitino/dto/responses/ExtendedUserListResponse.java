/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing metalake users with {@code origin} for the security UI. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ExtendedUserListResponse extends BaseResponse {

  @JsonProperty("users")
  private final ExtendedUserDTO[] users;

  /**
   * Creates a response with the given users.
   *
   * @param users Extended users.
   */
  public ExtendedUserListResponse(ExtendedUserDTO[] users) {
    super(0);
    this.users = users;
  }

  /** Jackson deserializer constructor. */
  public ExtendedUserListResponse() {
    super(0);
    this.users = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(users != null, "users must not be null");
  }
}
