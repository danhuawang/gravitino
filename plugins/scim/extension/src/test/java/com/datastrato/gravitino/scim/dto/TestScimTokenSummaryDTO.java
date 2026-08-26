/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.scim.model.ScimTokenSummary;
import org.junit.jupiter.api.Test;

class TestScimTokenSummaryDTO {

  @Test
  void testFromSummary() {
    ScimTokenSummary summary =
        ScimTokenSummary.builder()
            .withTokenName("prod")
            .withExpiresAt(5000L)
            .withStatus("valid")
            .withCreatedAt(1000L)
            .withLastUsedAt(2000L)
            .build();

    ScimTokenSummaryDTO dto = ScimTokenSummaryDTO.from(summary);

    assertEquals("prod", dto.getTokenName());
    assertEquals(5000L, dto.getExpiresAt());
    assertEquals("valid", dto.getStatus());
    assertEquals(1000L, dto.getCreatedAt());
    assertEquals(2000L, dto.getLastUsedAt());
  }
}
