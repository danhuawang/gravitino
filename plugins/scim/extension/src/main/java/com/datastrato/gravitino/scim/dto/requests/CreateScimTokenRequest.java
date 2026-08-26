/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/** Request body for creating a SCIM token. */
@Getter
@EqualsAndHashCode
@ToString
@Builder
@Jacksonized
public class CreateScimTokenRequest implements RESTRequest {

  @JsonProperty("tokenName")
  private final String tokenName;

  @JsonProperty("expiresInDays")
  @Nullable
  private final Integer expiresInDays;

  /** Default constructor for Jackson deserialization. */
  public CreateScimTokenRequest() {
    this(null, null);
  }

  /**
   * Creates a create-token request.
   *
   * @param tokenName token name
   * @param expiresInDays optional fixed lifetime in days
   */
  public CreateScimTokenRequest(String tokenName, @Nullable Integer expiresInDays) {
    this.tokenName = tokenName;
    this.expiresInDays = expiresInDays;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(tokenName), "tokenName must not be null or empty");
    if (expiresInDays != null) {
      Preconditions.checkArgument(expiresInDays > 0, "expiresInDays must be positive");
    }
  }
}
