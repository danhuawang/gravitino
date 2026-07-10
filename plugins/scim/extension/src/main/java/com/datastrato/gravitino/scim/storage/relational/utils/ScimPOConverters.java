/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational.utils;

import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.google.common.base.Preconditions;
import java.io.IOException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;

/** Converts SCIM token POs to domain objects. */
public final class ScimPOConverters {
  private ScimPOConverters() {}

  /**
   * Converts an active token row to a domain token.
   *
   * @param tokenMeta persisted token metadata
   * @return domain token metadata
   */
  public static ScimToken fromPO(ScimTokenMetaPO tokenMeta) {
    Preconditions.checkNotNull(tokenMeta, "tokenMeta must not be null");
    long expiresAt = tokenMeta.getExpiresAt() == null ? 0L : tokenMeta.getExpiresAt();
    return ScimToken.builder()
        .withTokenId(tokenMeta.getTokenId())
        .withMetalakeId(tokenMeta.getMetalakeId())
        .withTokenName(tokenMeta.getTokenName())
        .withExpiresAt(expiresAt)
        .withAuditInfo(deserializeAuditInfo(tokenMeta.getAuditInfo()))
        .build();
  }

  private static AuditInfo deserializeAuditInfo(String auditInfo) {
    if (auditInfo == null || auditInfo.isBlank()) {
      return AuditInfo.EMPTY;
    }
    try {
      return JsonUtils.anyFieldMapper().readValue(auditInfo, AuditInfo.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize audit info", e);
    }
  }
}
