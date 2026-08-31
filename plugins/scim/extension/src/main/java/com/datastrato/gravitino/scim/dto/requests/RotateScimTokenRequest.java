/*
 * Copyright 2026 Datastrato Inc.
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
import org.apache.gravitino.rest.RESTRequest;

/** Optional request body for rotating a SCIM token. */
@Getter
@EqualsAndHashCode
@ToString
@Builder
@Jacksonized
public class RotateScimTokenRequest implements RESTRequest {

  @JsonProperty("expiresInDays")
  @Nullable
  private final Integer expiresInDays;

  /** Default constructor for Jackson deserialization. */
  public RotateScimTokenRequest() {
    this(null);
  }

  /**
   * Creates a rotate-token request.
   *
   * @param expiresInDays optional new fixed lifetime in days
   */
  public RotateScimTokenRequest(@Nullable Integer expiresInDays) {
    this.expiresInDays = expiresInDays;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    if (expiresInDays != null) {
      Preconditions.checkArgument(expiresInDays > 0, "expiresInDays must be positive");
    }
  }
}
