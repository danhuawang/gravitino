/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Fileset DTO extended with tags and policies. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedFilesetDTO {

  @JsonUnwrapped @Nullable private final FilesetDTO filesetDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  /**
   * Constructs an ExtendedFilesetDTO.
   *
   * @param filesetDTO The base FilesetDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   */
  public ExtendedFilesetDTO(FilesetDTO filesetDTO, TagDTO[] tags, PolicyDTO[] policies) {
    this.filesetDTO = filesetDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedFilesetDTO() {
    this.filesetDTO = null;
    this.tags = null;
    this.policies = null;
  }

  /**
   * Returns the fileset name.
   *
   * @return The fileset name.
   */
  public String name() {
    return filesetDTO != null ? filesetDTO.name() : null;
  }

  /**
   * Validates the ExtendedFilesetDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(filesetDTO != null, "\"filesetDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(filesetDTO.name()), "fileset name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
  }
}
