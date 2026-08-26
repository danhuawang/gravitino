/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for deleting a SCIM token. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ScimTokenDeleteResponse extends BaseResponse {

  @JsonProperty("deleted")
  private final Boolean deleted;

  /**
   * Creates a delete response.
   *
   * @param deleted whether the token was soft-deleted
   */
  public ScimTokenDeleteResponse(Boolean deleted) {
    super(0);
    this.deleted = deleted;
  }

  /** Default constructor for Jackson deserialization. */
  public ScimTokenDeleteResponse() {
    super();
    this.deleted = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(deleted != null, "deleted must not be null");
  }
}
