/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.relational.utils;

import com.datastrato.gravitino.scim.v2.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.v2.model.ScimToken;
import com.datastrato.gravitino.scim.v2.model.ScimUserMeta;
import com.datastrato.gravitino.scim.v2.storage.po.ScimGroupMetaPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimUserMetaPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;

/** Converts SCIM v2 POs to domain objects. */
public final class ScimPOConverters {
  private ScimPOConverters() {}

  /** Converts an active token row to a domain token. */
  public static ScimToken fromPO(ScimTokenMetaPO tokenMeta) {
    Preconditions.checkNotNull(tokenMeta, "tokenMeta must not be null");
    long expiresAt = tokenMeta.getExpiresAt() == null ? 0L : tokenMeta.getExpiresAt();
    return ScimToken.builder()
        .withTokenId(tokenMeta.getTokenId())
        .withTokenName(tokenMeta.getTokenName())
        .withExpiresAt(expiresAt)
        .withAuditInfo(deserializeAuditInfo(tokenMeta.getAuditInfo()))
        .build();
  }

  /** Converts a user metadata row to a domain object. */
  public static ScimUserMeta fromUserPO(ScimUserMetaPO userMeta) {
    Preconditions.checkNotNull(userMeta, "userMeta must not be null");
    return ScimUserMeta.builder()
        .withUserId(userMeta.getUserId())
        .withUserName(userMeta.getUserName())
        .withExternalId(userMeta.getExternalId())
        .withEnabled(Boolean.TRUE.equals(userMeta.getEnabled()))
        .build();
  }

  /** Converts a group metadata row to a domain object. */
  public static ScimGroupMeta fromGroupPO(ScimGroupMetaPO groupMeta) {
    Preconditions.checkNotNull(groupMeta, "groupMeta must not be null");
    return ScimGroupMeta.builder()
        .withGroupId(groupMeta.getGroupId())
        .withGroupName(groupMeta.getGroupName())
        .withGroupComment(groupMeta.getGroupComment())
        .withExternalId(groupMeta.getExternalId())
        .build();
  }

  /** Serializes audit metadata for relational storage. */
  public static String serializeAuditInfo(AuditInfo auditInfo) {
    try {
      return JsonUtils.anyFieldMapper().writeValueAsString(auditInfo);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize audit info:", e);
    }
  }

  /** Deserializes audit metadata from relational storage. */
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
