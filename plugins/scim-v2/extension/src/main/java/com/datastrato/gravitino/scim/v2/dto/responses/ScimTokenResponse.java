/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.dto.responses;

import com.datastrato.gravitino.scim.v2.dto.ScimTokenDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for create and rotate SCIM token operations. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ScimTokenResponse extends BaseResponse {

  @JsonProperty("token")
  private final ScimTokenDTO token;

  /**
   * Creates a token response.
   *
   * @param token created or rotated token payload
   */
  public ScimTokenResponse(ScimTokenDTO token) {
    super(0);
    this.token = token;
  }

  /** Default constructor for Jackson deserialization. */
  public ScimTokenResponse() {
    super();
    this.token = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(token != null, "token must not be null");
  }
}
