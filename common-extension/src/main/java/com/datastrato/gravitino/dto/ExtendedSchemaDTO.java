/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Schema DTO extended with tags, policies, and direct child count status. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedSchemaDTO {

  @JsonUnwrapped @Nullable private final SchemaDTO schemaDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  @JsonProperty("directChildCount")
  @Nullable
  private final DirectChildCountDTO directChildCount;

  /**
   * Constructs an ExtendedSchemaDTO.
   *
   * @param schemaDTO The base SchemaDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   * @param directChildCount Direct child count and collection status.
   */
  public ExtendedSchemaDTO(
      SchemaDTO schemaDTO,
      TagDTO[] tags,
      PolicyDTO[] policies,
      DirectChildCountDTO directChildCount) {
    this.schemaDTO = schemaDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
    this.directChildCount = directChildCount;
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedSchemaDTO() {
    this.schemaDTO = null;
    this.tags = null;
    this.policies = null;
    this.directChildCount = null;
  }

  /**
   * Returns the schema name.
   *
   * @return The schema name.
   */
  public String name() {
    return schemaDTO != null ? schemaDTO.name() : null;
  }

  /**
   * Validates the ExtendedSchemaDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(schemaDTO != null, "\"schemaDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(schemaDTO.name()), "schema name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
    Preconditions.checkArgument(directChildCount != null, "\"directChildCount\" cannot be null");
    directChildCount.validate();
  }
}
