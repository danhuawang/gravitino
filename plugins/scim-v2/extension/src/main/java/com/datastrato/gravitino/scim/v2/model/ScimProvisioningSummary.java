/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.model;

import com.datastrato.gravitino.scim.v2.ScimUtils;
import com.datastrato.gravitino.scim.v2.storage.po.ScimProvisioningStatsPO;
import com.google.common.base.Preconditions;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Instance-scoped SCIM v2 provisioning overview row. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimProvisioningSummary {
  private final String endpoint;
  private final long tokenCount;
  private final long lastUsedAt;

  public static ScimProvisioningSummary from(ScimProvisioningStatsPO stats) {
    Preconditions.checkNotNull(stats, "stats must not be null");
    return ScimProvisioningSummary.builder()
        .withEndpoint(ScimUtils.scimV2BasePath())
        .withTokenCount(stats.getTokenCount() == null ? 0L : stats.getTokenCount())
        .withLastUsedAt(stats.getLastUsedAt() == null ? 0L : stats.getLastUsedAt())
        .build();
  }
}
