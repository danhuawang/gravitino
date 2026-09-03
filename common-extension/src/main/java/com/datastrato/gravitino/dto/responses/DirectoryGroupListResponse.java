/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing Directory Groups for the Configure → Directory page. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class DirectoryGroupListResponse extends BaseResponse {

  @JsonProperty("groups")
  private final DirectoryGroupDTO[] groups;

  /**
   * Creates a response with the given groups.
   *
   * @param groups Directory groups.
   */
  public DirectoryGroupListResponse(DirectoryGroupDTO[] groups) {
    super(0);
    this.groups = groups;
  }

  /** Jackson deserializer constructor. */
  public DirectoryGroupListResponse() {
    super(0);
    this.groups = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(groups != null, "groups must not be null");
  }
}
