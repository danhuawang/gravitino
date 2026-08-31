/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.model;

import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.google.common.base.Preconditions;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.meta.AuditInfo;

/** SCIM token metadata row for admin token list views (no plaintext secret). */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimTokenSummary {
  private static final String STATUS_VALID = "valid";
  private static final String STATUS_EXPIRED = "expired";

  private final String tokenName;
  private final long expiresAt;
  /** {@code valid} or {@code expired}. */
  private final String status;
  /** Creation time in epoch millis from audit metadata. */
  private final long createdAt;
  /** Last authenticated SCIM use in epoch millis; {@code 0} means never used. */
  private final long lastUsedAt;

  /**
   * Creates a token list summary from persisted token metadata.
   *
   * @param tokenMeta active token row
   * @param nowMillis current time in epoch millis used to derive {@code status}
   * @return summary row for admin list APIs
   */
  public static ScimTokenSummary from(ScimTokenMetaPO tokenMeta, long nowMillis) {
    Preconditions.checkNotNull(tokenMeta, "tokenMeta must not be null");
    long expiresAt = tokenMeta.getExpiresAt() == null ? 0L : tokenMeta.getExpiresAt();
    String status = expiresAt > 0L && nowMillis >= expiresAt ? STATUS_EXPIRED : STATUS_VALID;
    AuditInfo auditInfo = ScimPOConverters.deserializeAuditInfo(tokenMeta.getAuditInfo());
    long createdAt = auditInfo.createTime() == null ? 0L : auditInfo.createTime().toEpochMilli();
    long lastUsedAt = tokenMeta.getLastUsedAt() == null ? 0L : tokenMeta.getLastUsedAt();
    return ScimTokenSummary.builder()
        .withTokenName(tokenMeta.getTokenName())
        .withExpiresAt(expiresAt)
        .withStatus(status)
        .withCreatedAt(createdAt)
        .withLastUsedAt(lastUsedAt)
        .build();
  }
}
