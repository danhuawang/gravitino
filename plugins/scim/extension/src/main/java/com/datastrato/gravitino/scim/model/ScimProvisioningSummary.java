/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.model;

import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.storage.po.ScimProvisioningStatsPO;
import com.google.common.base.Preconditions;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Per-metalake SCIM provisioning overview row for the admin UI. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimProvisioningSummary {
  private final String metalake;
  private final String endpoint;
  private final long tokenCount;
  /** Epoch millis of the most recent token use; {@code 0} means never used. */
  private final long lastUsedAt;

  /**
   * Creates a provisioning overview row from aggregated stats.
   *
   * @param stats stats row for one metalake
   * @return provisioning overview row
   */
  public static ScimProvisioningSummary from(ScimProvisioningStatsPO stats) {
    Preconditions.checkNotNull(stats, "stats must not be null");
    String metalakeName = stats.getMetalakeName();
    return ScimProvisioningSummary.builder()
        .withMetalake(metalakeName)
        .withEndpoint(ScimUtils.metalakeBasePath(metalakeName))
        .withTokenCount(stats.getTokenCount() == null ? 0L : stats.getTokenCount())
        .withLastUsedAt(stats.getLastUsedAt() == null ? 0L : stats.getLastUsedAt())
        .build();
  }
}
