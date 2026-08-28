/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.IdentityType;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for user group lookup before adding a user into a metalake. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString
public class UserGroupNamesResponse extends BaseResponse {

  @JsonProperty("username")
  private String username;

  @JsonProperty("type")
  private IdentityType type;

  @JsonProperty("groupNames")
  private String[] groupNames;

  /** Default constructor for Jackson deserialization. */
  public UserGroupNamesResponse() {
    super(0);
    this.groupNames = new String[0];
  }

  /**
   * Creates a response.
   *
   * @param username The looked-up username.
   * @param type The identity type.
   * @param groupNames Group names for the user.
   */
  public UserGroupNamesResponse(String username, IdentityType type, @Nullable String[] groupNames) {
    super(0);
    this.username = username;
    this.type = type;
    this.groupNames = groupNames == null ? new String[0] : groupNames;
  }
}
