/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing one metalake group with {@code origin} for the security UI. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ExtendedGroupResponse extends BaseResponse {

  @JsonProperty("group")
  private final ExtendedGroupDTO group;

  /**
   * Creates a response with the given group.
   *
   * @param group The extended group.
   */
  public ExtendedGroupResponse(ExtendedGroupDTO group) {
    super(0);
    this.group = group;
  }

  /** Jackson deserializer constructor. */
  public ExtendedGroupResponse() {
    super(0);
    this.group = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(group != null, "group must not be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(group.name()), "group 'name' must not be null and empty");
  }
}
