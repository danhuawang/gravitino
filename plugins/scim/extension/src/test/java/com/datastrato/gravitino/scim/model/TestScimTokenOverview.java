/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.model;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScimTokenOverview {

  @Test
  void testOf() {
    ScimTokenSummary entra =
        ScimTokenSummary.builder()
            .withTokenName("entra")
            .withExpiresAt(0L)
            .withStatus("valid")
            .withCreatedAt(1000L)
            .withLastUsedAt(3000L)
            .build();
    ScimTokenSummary oldOkta =
        ScimTokenSummary.builder()
            .withTokenName("old-okta")
            .withExpiresAt(0L)
            .withStatus("valid")
            .withCreatedAt(500L)
            .withLastUsedAt(0L)
            .build();

    ScimTokenOverview overview = ScimTokenOverview.of(3000L, List.of(entra, oldOkta));

    Assertions.assertEquals(3000L, overview.getLastUsedAt());
    Assertions.assertEquals(2L, overview.getTokenCount());
    Assertions.assertEquals(2, overview.getTokens().size());
    Assertions.assertEquals("entra", overview.getTokens().get(0).getTokenName());
  }

  @Test
  void testOfRejectsNullTokens() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> ScimTokenOverview.of(0L, null));
  }
}
