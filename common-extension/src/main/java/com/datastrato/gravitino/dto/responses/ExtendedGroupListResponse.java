/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing metalake groups with {@code origin} for the security UI. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ExtendedGroupListResponse extends BaseResponse {

  @JsonProperty("groups")
  private final ExtendedGroupDTO[] groups;

  /**
   * Creates a response with the given groups.
   *
   * @param groups Extended groups.
   */
  public ExtendedGroupListResponse(ExtendedGroupDTO[] groups) {
    super(0);
    this.groups = groups;
  }

  /** Jackson deserializer constructor. */
  public ExtendedGroupListResponse() {
    super(0);
    this.groups = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(groups != null, "groups must not be null");
  }
}
