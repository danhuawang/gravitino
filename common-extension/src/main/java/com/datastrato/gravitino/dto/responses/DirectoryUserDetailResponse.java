/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.DirectoryUserDetailDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing one Directory User detail for Overview. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class DirectoryUserDetailResponse extends BaseResponse {

  @JsonProperty("user")
  private final DirectoryUserDetailDTO user;

  /**
   * Creates a response with the given Directory User detail.
   *
   * @param user Directory user detail.
   */
  public DirectoryUserDetailResponse(DirectoryUserDetailDTO user) {
    super(0);
    this.user = user;
  }

  /** Jackson deserializer constructor. */
  public DirectoryUserDetailResponse() {
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
