/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.dto.MetalakeDTO;
import org.apache.gravitino.dto.authorization.OwnerDTO;

/** Represents a Metalake DTO extended with its direct child count and owner. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedMetalakeDTO {

  @JsonUnwrapped @Nullable private final MetalakeDTO metalakeDTO;

  @JsonProperty("directChildCounts")
  @Nullable
  private final Long directChildCounts;

  @EqualsAndHashCode.Exclude
  @JsonProperty("owner")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final OwnerDTO owner;

  /**
   * Constructs an ExtendedMetalakeDTO.
   *
   * @param metalakeDTO The base MetalakeDTO.
   * @param directChildCounts Count of visible direct child catalogs, or {@code null} when
   *     unavailable.
   */
  public ExtendedMetalakeDTO(MetalakeDTO metalakeDTO, @Nullable Long directChildCounts) {
    this(metalakeDTO, directChildCounts, null);
  }

  /**
   * Constructs an ExtendedMetalakeDTO with ownership information.
   *
   * @param metalakeDTO The base MetalakeDTO.
   * @param directChildCounts The visible direct catalog count, or null when unavailable.
   * @param owner The owner, or null when authorization is disabled or no owner is assigned.
   */
  public ExtendedMetalakeDTO(
      MetalakeDTO metalakeDTO, @Nullable Long directChildCounts, @Nullable OwnerDTO owner) {
    this.metalakeDTO = metalakeDTO;
    this.directChildCounts = directChildCounts;
    this.owner = owner;
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedMetalakeDTO() {
    this.metalakeDTO = null;
    this.directChildCounts = null;
    this.owner = null;
  }

  /**
   * Returns the Metalake name.
   *
   * @return The Metalake name.
   */
  public String name() {
    return metalakeDTO != null ? metalakeDTO.name() : null;
  }

  /**
   * Validates the ExtendedMetalakeDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(metalakeDTO != null, "\"metalakeDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(metalakeDTO.name()), "metalake name cannot be blank");
    Preconditions.checkArgument(metalakeDTO.auditInfo() != null, "metalake audit cannot be null");
    if (directChildCounts != null) {
      Preconditions.checkArgument(directChildCounts >= 0, "directChildCounts cannot be negative");
    }
  }
  // OwnerDTO does not define value equality; compare its public identity fields.
  @EqualsAndHashCode.Include
  @Nullable
  private String ownerNameForEquality() {
    return owner == null ? null : owner.name();
  }

  @EqualsAndHashCode.Include
  @Nullable
  private Owner.Type ownerTypeForEquality() {
    return owner == null ? null : owner.type();
  }
}
