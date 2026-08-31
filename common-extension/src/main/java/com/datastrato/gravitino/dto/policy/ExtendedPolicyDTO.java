/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.policy.PolicyDTO;

/** Data transfer object representing a policy with its associated objects count. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedPolicyDTO {

  @JsonUnwrapped private final PolicyDTO policy;

  @JsonProperty("associatedObjectsCount")
  private final int associatedObjectsCount;

  /** Default constructor for Jackson deserialization. */
  protected ExtendedPolicyDTO() {
    this.policy = null;
    this.associatedObjectsCount = 0;
  }

  /**
   * Creates a new instance of ExtendedPolicyDTO.
   *
   * @param policy The policy DTO.
   * @param associatedObjectsCount The number of directly associated metadata objects.
   */
  private ExtendedPolicyDTO(PolicyDTO policy, int associatedObjectsCount) {
    this.policy = policy;
    this.associatedObjectsCount = associatedObjectsCount;
  }

  /**
   * Creates a new Builder for constructing an ExtendedPolicyDTO.
   *
   * @return A new Builder instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for constructing an ExtendedPolicyDTO instance. */
  public static class Builder {
    private PolicyDTO policy;
    private int associatedObjectsCount;

    /**
     * Sets the policy DTO.
     *
     * @param policy The policy DTO.
     * @return The builder instance.
     */
    public Builder withPolicy(PolicyDTO policy) {
      this.policy = policy;
      return this;
    }

    /**
     * Sets the associated objects count.
     *
     * @param associatedObjectsCount The associated objects count.
     * @return The builder instance.
     */
    public Builder withAssociatedObjectsCount(int associatedObjectsCount) {
      this.associatedObjectsCount = associatedObjectsCount;
      return this;
    }

    /**
     * Builds an instance of ExtendedPolicyDTO using the builder's properties.
     *
     * @return An instance of ExtendedPolicyDTO.
     * @throws IllegalArgumentException If the policy is null or associatedObjectsCount is negative.
     */
    public ExtendedPolicyDTO build() {
      Preconditions.checkArgument(policy != null, "policy cannot be null");
      Preconditions.checkArgument(
          associatedObjectsCount >= 0, "associatedObjectsCount cannot be negative");

      return new ExtendedPolicyDTO(policy, associatedObjectsCount);
    }
  }
}
