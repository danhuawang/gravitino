/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.v2.storage.relational.utils.ScimPOConverters;
import java.time.Instant;
import org.apache.gravitino.meta.AuditInfo;
import org.junit.jupiter.api.Test;

class TestScimTokenSummary {

  @Test
  void testFromValidToken() {
    AuditInfo auditInfo =
        AuditInfo.builder()
            .withCreator("alice")
            .withCreateTime(Instant.ofEpochMilli(1000L))
            .build();
    ScimTokenMetaPO tokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withTokenName("prod")
            .withTokenHash("hash")
            .withExpiresAt(5000L)
            .withAuditInfo(ScimPOConverters.serializeAuditInfo(auditInfo))
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .withLastUsedAt(2000L)
            .build();

    ScimTokenSummary summary = ScimTokenSummary.from(tokenMeta, 3000L);

    assertEquals("prod", summary.getTokenName());
    assertEquals(5000L, summary.getExpiresAt());
    assertEquals("valid", summary.getStatus());
    assertEquals(1000L, summary.getCreatedAt());
    assertEquals(2000L, summary.getLastUsedAt());
  }

  @Test
  void testFromExpiredToken() {
    ScimTokenMetaPO tokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withTokenName("prod")
            .withTokenHash("hash")
            .withExpiresAt(1000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .withLastUsedAt(0L)
            .build();

    ScimTokenSummary summary = ScimTokenSummary.from(tokenMeta, 1000L);

    assertEquals("expired", summary.getStatus());
  }
}
