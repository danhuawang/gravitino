/*
 * Copyright 2026 Datastrato Inc.
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
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Table DTO extended with tags and policies. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedTableDTO {

  @JsonUnwrapped @Nullable private final TableDTO tableDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  /**
   * Constructs an ExtendedTableDTO.
   *
   * @param tableDTO The base TableDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   */
  public ExtendedTableDTO(TableDTO tableDTO, TagDTO[] tags, PolicyDTO[] policies) {
    this.tableDTO = tableDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedTableDTO() {
    this.tableDTO = null;
    this.tags = null;
    this.policies = null;
  }

  /**
   * Returns the table name.
   *
   * @return The table name.
   */
  public String name() {
    return tableDTO != null ? tableDTO.name() : null;
  }

  /**
   * Validates the ExtendedTableDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(tableDTO != null, "\"tableDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(tableDTO.name()), "table name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
  }
}
