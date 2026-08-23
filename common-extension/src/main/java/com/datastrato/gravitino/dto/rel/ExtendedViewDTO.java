/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.rel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a View DTO extended with tags and policies. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedViewDTO {

  @JsonUnwrapped @Nullable private final ViewDTO viewDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  /**
   * Constructs an ExtendedViewDTO.
   *
   * @param viewDTO The base ViewDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   */
  public ExtendedViewDTO(ViewDTO viewDTO, TagDTO[] tags, PolicyDTO[] policies) {
    this.viewDTO = viewDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedViewDTO() {
    this.viewDTO = null;
    this.tags = null;
    this.policies = null;
  }

  /**
   * Returns the view name.
   *
   * @return The view name.
   */
  public String name() {
    return viewDTO != null ? viewDTO.name() : null;
  }

  /**
   * Validates the ExtendedViewDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(viewDTO != null, "\"viewDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(viewDTO.name()), "view name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
  }
}
