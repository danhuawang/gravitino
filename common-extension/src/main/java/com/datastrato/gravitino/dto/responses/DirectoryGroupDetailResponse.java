/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.DirectoryGroupDetailDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing one Directory Group detail for Overview. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class DirectoryGroupDetailResponse extends BaseResponse {

  @JsonProperty("group")
  private final DirectoryGroupDetailDTO group;

  /**
   * Creates a response with the given Directory Group detail.
   *
   * @param group Directory group detail.
   */
  public DirectoryGroupDetailResponse(DirectoryGroupDetailDTO group) {
    super(0);
    this.group = group;
  }

  /** Jackson deserializer constructor. */
  public DirectoryGroupDetailResponse() {
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
