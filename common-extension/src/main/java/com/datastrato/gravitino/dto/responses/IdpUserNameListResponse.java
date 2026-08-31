/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.IdpNameStatusDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response listing built-in IdP users with metalake membership {@code status}. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class IdpUserNameListResponse extends BaseResponse {

  @JsonProperty("users")
  private final IdpNameStatusDTO[] users;

  /**
   * Creates a response with the given users.
   *
   * @param users IdP usernames with membership status.
   */
  public IdpUserNameListResponse(IdpNameStatusDTO[] users) {
    super(0);
    this.users = users;
  }

  /** Jackson deserializer constructor. */
  public IdpUserNameListResponse() {
    super(0);
    this.users = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(users != null, "users must not be null");
    for (IdpNameStatusDTO user : users) {
      Preconditions.checkArgument(user != null, "users must not contain null");
      user.validate();
    }
  }
}
