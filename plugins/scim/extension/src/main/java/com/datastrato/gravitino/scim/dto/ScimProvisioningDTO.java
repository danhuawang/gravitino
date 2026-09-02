/*
 * Copyright 2026 Datastrato Inc.
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

/** SCIM provisioning overview row. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimProvisioningDTO {
  @JsonProperty("endpoint")
  private final String endpoint;

  @JsonProperty("tokenCount")
  private final long tokenCount;

  @JsonProperty("lastUsedAt")
  private final long lastUsedAt;

  public ScimProvisioningDTO() {
    this(null, 0L, 0L);
  }

  public static ScimProvisioningDTO from(ScimProvisioningSummary summary) {
    return ScimProvisioningDTO.builder()
        .withEndpoint(summary.getEndpoint())
        .withTokenCount(summary.getTokenCount())
        .withLastUsedAt(summary.getLastUsedAt())
        .build();
  }
}
