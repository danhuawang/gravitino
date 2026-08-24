/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.MetalakeDTO;

/** Represents a Metalake DTO extended with its direct child count. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedMetalakeDTO {

  @JsonUnwrapped @Nullable private final MetalakeDTO metalakeDTO;

  @JsonProperty("directChildCounts")
  @Nullable
  private final Long directChildCounts;

  /**
   * Constructs an ExtendedMetalakeDTO.
   *
   * @param metalakeDTO The base MetalakeDTO.
   * @param directChildCounts Count of visible direct child catalogs, or {@code null} when
   *     unavailable.
   */
  public ExtendedMetalakeDTO(MetalakeDTO metalakeDTO, @Nullable Long directChildCounts) {
    this.metalakeDTO = metalakeDTO;
    this.directChildCounts = directChildCounts;
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedMetalakeDTO() {
    this.metalakeDTO = null;
    this.directChildCounts = null;
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
}
