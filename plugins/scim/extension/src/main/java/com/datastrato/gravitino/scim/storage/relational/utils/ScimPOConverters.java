/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational.utils;

import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
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

  /**
   * Serializes audit metadata for relational storage.
   *
   * @param auditInfo audit metadata
   * @return serialized audit metadata JSON
   */
  public static String serializeAuditInfo(AuditInfo auditInfo) {
    try {
      return JsonUtils.anyFieldMapper().writeValueAsString(auditInfo);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize audit info:", e);
    }
  }

  /**
   * Deserializes audit metadata from relational storage.
   *
   * @param auditInfo serialized audit metadata JSON
   * @return audit metadata
   */
  public static AuditInfo deserializeAuditInfo(String auditInfo) {
    if (StringUtils.isBlank(auditInfo)) {
      return AuditInfo.EMPTY;
    }
    try {
      return JsonUtils.anyFieldMapper().readValue(auditInfo, AuditInfo.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize audit info:", e);
    }
  }
}
