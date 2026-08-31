/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.ExtendedMetalakeDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of extended Metalakes. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class MetalakeListResponse extends BaseResponse {

  @JsonProperty("metalakes")
  private final ExtendedMetalakeDTO[] metalakes;

  /**
   * Creates a new MetalakeListResponse.
   *
   * @param metalakes The list of extended Metalakes.
   */
  public MetalakeListResponse(ExtendedMetalakeDTO[] metalakes) {
    super(0);
    this.metalakes = metalakes;
  }

  /** Default constructor for Jackson deserialization. */
  public MetalakeListResponse() {
    super();
    this.metalakes = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(metalakes != null, "\"metalakes\" cannot be null");
    Arrays.stream(metalakes)
        .forEach(
            metalake -> {
              Preconditions.checkArgument(metalake != null, "metalake cannot be null");
              metalake.validate();
            });
  }
}
