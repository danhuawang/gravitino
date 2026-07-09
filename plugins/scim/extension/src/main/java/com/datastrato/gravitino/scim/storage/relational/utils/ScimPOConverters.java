/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational.utils;

import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.time.Instant;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;

/** Converts SCIM token POs and audit metadata for the service layer. */
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
   * Serializes audit metadata for a newly created token.
   *
   * @param creator creator username
   * @param createTime creation timestamp
   * @return serialized audit JSON
   */
  public static String newAuditInfo(String creator, Instant createTime) {
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator(creator).withCreateTime(createTime).build();
    return serializeAuditInfo(auditInfo);
  }

  /**
   * Serializes audit metadata after an update while preserving the original creator.
   *
   * @param existingAuditInfo existing serialized audit JSON
   * @param modifier modifier username
   * @param lastModifiedTime last modified timestamp
   * @return serialized audit JSON
   */
  public static String updatedAuditInfo(
      String existingAuditInfo, String modifier, Instant lastModifiedTime) {
    AuditInfo existing = deserializeAuditInfo(existingAuditInfo);
    AuditInfo updated =
        AuditInfo.builder()
            .withCreator(existing.creator())
            .withCreateTime(existing.createTime())
            .withLastModifier(modifier)
            .withLastModifiedTime(lastModifiedTime)
            .build();
    return serializeAuditInfo(updated);
  }

  private static String serializeAuditInfo(AuditInfo auditInfo) {
    try {
      return JsonUtils.anyFieldMapper().writeValueAsString(auditInfo);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize audit info", e);
    }
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
