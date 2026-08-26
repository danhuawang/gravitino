/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import java.time.Instant;
import org.apache.gravitino.meta.AuditInfo;
import org.junit.jupiter.api.Test;

class TestScimPOConverters {

  @Test
  void testAuditInfo() {
    Instant createTime = Instant.parse("2026-01-01T00:00:00Z");
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("alice").withCreateTime(createTime).build();

    String serialized = ScimPOConverters.serializeAuditInfo(auditInfo);
    AuditInfo deserialized = ScimPOConverters.deserializeAuditInfo(serialized);

    assertEquals(auditInfo.creator(), deserialized.creator());
    assertEquals(auditInfo.createTime(), deserialized.createTime());
  }

  @Test
  void testFromPO() {
    Instant createTime = Instant.parse("2026-01-01T00:00:00Z");
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("alice").withCreateTime(createTime).build();
    ScimTokenMetaPO tokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("prod")
            .withTokenHash("hash-a")
            .withExpiresAt(2000L)
            .withAuditInfo("{\"creator\":\"alice\",\"createTime\":\"2026-01-01T00:00:00Z\"}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();

    ScimToken token = ScimPOConverters.fromPO(tokenMeta);

    assertEquals(1L, token.getTokenId());
    assertEquals(10L, token.getMetalakeId());
    assertEquals("prod", token.getTokenName());
    assertEquals(2000L, token.getExpiresAt());
    assertEquals(auditInfo.creator(), token.getAuditInfo().creator());
    assertEquals(auditInfo.createTime(), token.getAuditInfo().createTime());
  }

  @Test
  void testNullExpiresAt() {
    ScimTokenMetaPO tokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("prod")
            .withTokenHash("hash-a")
            .withExpiresAt(null)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();

    ScimToken token = ScimPOConverters.fromPO(tokenMeta);

    assertEquals(0L, token.getExpiresAt());
    assertNull(token.getAuditInfo().creator());
  }
}
