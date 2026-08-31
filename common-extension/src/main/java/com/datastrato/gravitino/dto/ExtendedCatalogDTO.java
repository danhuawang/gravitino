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
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Catalog DTO extended with tags, policies, and direct child counts. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedCatalogDTO {

  @JsonUnwrapped @Nullable private final CatalogDTO catalogDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  @JsonProperty("directChildCounts")
  @Nullable
  private final Long directChildCounts;

  /**
   * Constructs an ExtendedCatalogDTO.
   *
   * @param catalogDTO The base CatalogDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   * @param directChildCounts Count of direct child entities, or {@code null} when unavailable.
   */
  public ExtendedCatalogDTO(
      CatalogDTO catalogDTO,
      TagDTO[] tags,
      PolicyDTO[] policies,
      @Nullable Long directChildCounts) {
    this.catalogDTO = catalogDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
    this.directChildCounts = directChildCounts;
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedCatalogDTO() {
    this.catalogDTO = null;
    this.tags = null;
    this.policies = null;
    this.directChildCounts = null;
  }

  /**
   * Returns the catalog name.
   *
   * @return The catalog name.
   */
  public String name() {
    return catalogDTO != null ? catalogDTO.name() : null;
  }

  /**
   * Validates the ExtendedCatalogDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(catalogDTO != null, "\"catalogDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalogDTO.name()), "catalog name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
    if (directChildCounts != null) {
      Preconditions.checkArgument(directChildCounts >= 0, "directChildCounts cannot be negative");
    }
  }
}
