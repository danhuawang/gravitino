/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.dto;

import com.datastrato.gravitino.scim.model.ScimProvisioningSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** One metalake row in the SCIM Provisioning overview list. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimProvisioningDTO {

  @JsonProperty("metalake")
  private final String metalake;

  @JsonProperty("endpoint")
  private final String endpoint;

  @JsonProperty("tokenCount")
  private final long tokenCount;

  @JsonProperty("lastUsedAt")
  private final long lastUsedAt;

  /** Default constructor for Jackson deserialization. */
  public ScimProvisioningDTO() {
    this(null, null, 0L, 0L);
  }

  /**
   * Creates a provisioning overview DTO.
   *
   * @param metalake metalake name
   * @param endpoint SCIM base path for the metalake
   * @param tokenCount active token count
   * @param lastUsedAt max {@code last_used_at}; {@code 0} means never
   * @return DTO
   */
  public static ScimProvisioningDTO of(
      String metalake, String endpoint, long tokenCount, long lastUsedAt) {
    return ScimProvisioningDTO.builder()
        .withMetalake(metalake)
        .withEndpoint(endpoint)
        .withTokenCount(tokenCount)
        .withLastUsedAt(lastUsedAt)
        .build();
  }

  /**
   * Creates a provisioning overview DTO from a service-layer summary.
   *
   * @param summary provisioning overview row
   * @return DTO
   */
  public static ScimProvisioningDTO from(ScimProvisioningSummary summary) {
    return of(
        summary.getMetalake(),
        summary.getEndpoint(),
        summary.getTokenCount(),
        summary.getLastUsedAt());
  }
}
