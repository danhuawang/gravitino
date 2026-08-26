/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.po;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestScimTokenMetaPO {

  @Test
  public void testScimTokenMetaPOBuilder() {
    ScimTokenMetaPO tokenMeta =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("scim-token")
            .withTokenHash("abc123")
            .withExpiresAt(1000L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(500L)
            .withLastUsedAt(0L)
            .build();

    Assertions.assertEquals(1L, tokenMeta.getTokenId());
    Assertions.assertEquals(10L, tokenMeta.getMetalakeId());
    Assertions.assertEquals("scim-token", tokenMeta.getTokenName());
    Assertions.assertEquals("abc123", tokenMeta.getTokenHash());
    Assertions.assertEquals(1000L, tokenMeta.getExpiresAt());
    Assertions.assertEquals("{}", tokenMeta.getAuditInfo());
    Assertions.assertEquals(0L, tokenMeta.getDeletedAt());
    Assertions.assertEquals(500L, tokenMeta.getUpdatedAt());
    Assertions.assertEquals(0L, tokenMeta.getLastUsedAt());
  }

  @Test
  public void testEqualsAndHashCode() {
    ScimTokenMetaPO tokenMeta1 =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("scim-token")
            .withTokenHash("abc123")
            .withExpiresAt(0L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();

    ScimTokenMetaPO tokenMeta2 =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("scim-token")
            .withTokenHash("abc123")
            .withExpiresAt(0L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L)
            .build();

    Assertions.assertEquals(tokenMeta1, tokenMeta2);
    Assertions.assertEquals(tokenMeta1.hashCode(), tokenMeta2.hashCode());
  }

  @Test
  public void testBuilderReuseDoesNotMutateBuiltObject() {
    var builder =
        ScimTokenMetaPO.builder()
            .withTokenId(1L)
            .withMetalakeId(10L)
            .withTokenName("scim-token")
            .withTokenHash("abc123")
            .withExpiresAt(0L)
            .withAuditInfo("{}")
            .withDeletedAt(0L)
            .withUpdatedAt(0L);

    ScimTokenMetaPO firstToken = builder.build();
    ScimTokenMetaPO secondToken = builder.withTokenName("other-token").build();

    Assertions.assertEquals("scim-token", firstToken.getTokenName());
    Assertions.assertEquals("other-token", secondToken.getTokenName());
  }
}
