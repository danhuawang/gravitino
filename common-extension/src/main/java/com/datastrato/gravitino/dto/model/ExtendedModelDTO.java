/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Model DTO extended with tags and policies. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedModelDTO {

  @JsonUnwrapped @Nullable private final ModelDTO modelDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  /**
   * Constructs an ExtendedModelDTO.
   *
   * @param modelDTO The base ModelDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   */
  public ExtendedModelDTO(ModelDTO modelDTO, TagDTO[] tags, PolicyDTO[] policies) {
    this.modelDTO = modelDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedModelDTO() {
    this.modelDTO = null;
    this.tags = null;
    this.policies = null;
  }

  /**
   * Returns the model name.
   *
   * @return The model name.
   */
  public String name() {
    return modelDTO != null ? modelDTO.name() : null;
  }

  /**
   * Validates the ExtendedModelDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(modelDTO != null, "\"modelDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(modelDTO.name()), "model name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
  }
}
