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
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing one metalake user with {@code origin} for the security UI. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ExtendedUserResponse extends BaseResponse {

  @JsonProperty("user")
  private final ExtendedUserDTO user;

  /**
   * Creates a response with the given user.
   *
   * @param user The extended user.
   */
  public ExtendedUserResponse(ExtendedUserDTO user) {
    super(0);
    this.user = user;
  }

  /** Jackson deserializer constructor. */
  public ExtendedUserResponse() {
    super(0);
    this.user = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(user != null, "user must not be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(user.name()), "user 'name' must not be null and empty");
  }
}
