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

/** Response listing built-in IdP groups with metalake membership {@code status}. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class IdpGroupNameListResponse extends BaseResponse {

  @JsonProperty("groups")
  private final IdpNameStatusDTO[] groups;

  /**
   * Creates a response with the given groups.
   *
   * @param groups IdP group names with membership status.
   */
  public IdpGroupNameListResponse(IdpNameStatusDTO[] groups) {
    super(0);
    this.groups = groups;
  }

  /** Jackson deserializer constructor. */
  public IdpGroupNameListResponse() {
    super(0);
    this.groups = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(groups != null, "groups must not be null");
    for (IdpNameStatusDTO group : groups) {
      Preconditions.checkArgument(group != null, "groups must not contain null");
      group.validate();
    }
  }
}
