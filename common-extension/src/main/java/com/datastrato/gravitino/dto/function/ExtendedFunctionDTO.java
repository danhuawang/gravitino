/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Function DTO extended with tags and policies. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedFunctionDTO {

  @JsonUnwrapped @Nullable private final FunctionDTO functionDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  /**
   * Constructs an ExtendedFunctionDTO.
   *
   * @param functionDTO The base FunctionDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   */
  public ExtendedFunctionDTO(FunctionDTO functionDTO, TagDTO[] tags, PolicyDTO[] policies) {
    this.functionDTO = functionDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedFunctionDTO() {
    this.functionDTO = null;
    this.tags = null;
    this.policies = null;
  }

  /**
   * Returns the function name.
   *
   * @return The function name.
   */
  public String name() {
    return functionDTO != null ? functionDTO.name() : null;
  }

  /**
   * Validates the ExtendedFunctionDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(functionDTO != null, "\"functionDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(functionDTO.name()), "function name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
  }
}
