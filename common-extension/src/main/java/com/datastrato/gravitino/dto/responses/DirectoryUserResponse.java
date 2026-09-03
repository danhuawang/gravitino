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
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing one Directory User. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class DirectoryUserResponse extends BaseResponse {

  @JsonProperty("user")
  private final DirectoryUserDTO user;

  /**
   * Creates a response with the given Directory User.
   *
   * @param user Directory user.
   */
  public DirectoryUserResponse(DirectoryUserDTO user) {
    super(0);
    this.user = user;
  }

  /** Jackson deserializer constructor. */
  public DirectoryUserResponse() {
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
