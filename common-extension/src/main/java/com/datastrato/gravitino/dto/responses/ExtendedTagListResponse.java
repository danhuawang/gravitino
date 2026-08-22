/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.tag.ExtendedTagDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response containing tags and their owners. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ExtendedTagListResponse extends BaseResponse {

  @JsonProperty("tags")
  private final ExtendedTagDTO[] tags;

  /**
   * Creates an ExtendedTagListResponse.
   *
   * @param tags The extended tag DTOs.
   */
  public ExtendedTagListResponse(ExtendedTagDTO[] tags) {
    super(0);
    this.tags = tags;
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedTagListResponse() {
    super();
    this.tags = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(
            tag -> {
              Preconditions.checkArgument(tag != null, "tag cannot be null");
              Preconditions.checkArgument(tag.tag() != null, "inner tag cannot be null");
              if (tag.owner() != null) {
                Preconditions.checkArgument(
                    StringUtils.isNotBlank(tag.owner().name()),
                    "owner 'name' must not be null or empty");
                Preconditions.checkArgument(
                    tag.owner().type() != null, "owner 'type' must not be null");
              }
            });
  }
}
