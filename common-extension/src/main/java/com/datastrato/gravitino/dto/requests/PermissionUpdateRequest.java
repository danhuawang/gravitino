/*
 * Copyright 2025 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.authorization.SecurableObjectDTO;
import org.apache.gravitino.rest.RESTRequest;

@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class PermissionUpdateRequest implements RESTRequest {

  @JsonProperty("updates")
  private SecurableObjectDTO[] updates;

  /** Default constructor for Jackson deserialization. */
  private PermissionUpdateRequest() {
    this(null);
  }

  /** Creates a new PermissionUpdateRequest. */
  public PermissionUpdateRequest(SecurableObjectDTO[] updates) {
    this.updates = updates;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        updates != null && updates.length > 0, "updates cannot be null or empty");
  }
}
