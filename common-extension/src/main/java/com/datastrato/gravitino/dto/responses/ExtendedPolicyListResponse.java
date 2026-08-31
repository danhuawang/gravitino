/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.policy.ExtendedPolicyDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response containing a list of extended policy objects. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ExtendedPolicyListResponse extends BaseResponse {

  @JsonProperty("policies")
  private final ExtendedPolicyDTO[] policies;

  /**
   * Creates a new ExtendedPolicyListResponse.
   *
   * @param policies The array of extended policy DTOs.
   */
  public ExtendedPolicyListResponse(ExtendedPolicyDTO[] policies) {
    super(0);
    this.policies = policies;
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedPolicyListResponse() {
    super();
    this.policies = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(
            policy -> {
              Preconditions.checkArgument(policy != null, "policy cannot be null");
              Preconditions.checkArgument(
                  policy.getPolicy() != null, "inner policy cannot be null");
              Preconditions.checkArgument(
                  policy.getAssociatedObjectsCount() >= 0,
                  "associatedObjectsCount cannot be negative");
            });
  }
}
