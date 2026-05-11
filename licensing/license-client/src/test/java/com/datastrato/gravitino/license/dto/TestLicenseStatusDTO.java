/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.dto;

import com.datastrato.gravitino.license.LicenseManager;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestLicenseStatusDTO {

  private static LicenseManager.LicenseStatus buildStatus(
      LicenseManager.StatusCode code, long expiresAtDays, long daysRemaining, int maxNodes) {
    LicenseManager.LicenseStatus s =
        new LicenseManager.LicenseStatus(
            code,
            "Acme Corp",
            "550e8400-e29b-41d4-a716-446655440000",
            expiresAtDays,
            daysRemaining,
            30,
            maxNodes);
    s.setActiveNodes(3);
    return s;
  }

  @Test
  void testFromMapsAllFields() {
    // expiresAtDays=20265 → 2025-06-26
    LicenseManager.LicenseStatus s = buildStatus(LicenseManager.StatusCode.VALID, 20265L, 47L, 5);

    LicenseStatusDTO dto = LicenseStatusDTO.from(s);

    Assertions.assertEquals("Acme Corp", dto.getIssuedTo());
    Assertions.assertEquals("550e8400-e29b-41d4-a716-446655440000", dto.getLicenseId());
    Assertions.assertEquals("2025-06-26T00:00:00Z", dto.getExpiresAt());
    Assertions.assertEquals(47L, dto.getDaysRemaining());
    Assertions.assertEquals(30, dto.getGracePeriodDays());
    Assertions.assertEquals("VALID", dto.getStatus());
    Assertions.assertEquals(5, dto.getMaxNodes());
    Assertions.assertEquals(3, dto.getActiveNodes());
  }

  @Test
  void testStatusCodesAreMappedByName() {
    for (LicenseManager.StatusCode code : LicenseManager.StatusCode.values()) {
      LicenseStatusDTO dto = LicenseStatusDTO.from(buildStatus(code, 20265L, 10L, 5));
      Assertions.assertEquals(code.name(), dto.getStatus());
    }
  }

  @Test
  void testUnlimitedNodes() {
    LicenseStatusDTO dto =
        LicenseStatusDTO.from(buildStatus(LicenseManager.StatusCode.VALID, 20265L, 47L, -1));
    Assertions.assertEquals(-1, dto.getMaxNodes());
  }

  @Test
  void testExpiresAtDateFormat() {
    // day 0 = 1970-01-01
    LicenseStatusDTO dto =
        LicenseStatusDTO.from(buildStatus(LicenseManager.StatusCode.EXPIRED, 1L, 0L, 5));
    Assertions.assertEquals("1970-01-02T00:00:00Z", dto.getExpiresAt());
  }

  @Test
  void testJsonRoundTrip() throws Exception {
    LicenseStatusDTO original =
        LicenseStatusDTO.from(buildStatus(LicenseManager.StatusCode.EXPIRING_SOON, 20265L, 5L, 10));

    String json = JsonUtils.objectMapper().writeValueAsString(original);
    LicenseStatusDTO deserialized =
        JsonUtils.objectMapper().readValue(json, LicenseStatusDTO.class);

    Assertions.assertEquals(original.getIssuedTo(), deserialized.getIssuedTo());
    Assertions.assertEquals(original.getLicenseId(), deserialized.getLicenseId());
    Assertions.assertEquals(original.getExpiresAt(), deserialized.getExpiresAt());
    Assertions.assertEquals(original.getDaysRemaining(), deserialized.getDaysRemaining());
    Assertions.assertEquals(original.getGracePeriodDays(), deserialized.getGracePeriodDays());
    Assertions.assertEquals(original.getStatus(), deserialized.getStatus());
    Assertions.assertEquals(original.getMaxNodes(), deserialized.getMaxNodes());
    Assertions.assertEquals(original.getActiveNodes(), deserialized.getActiveNodes());
  }

  @Test
  void testJsonFieldNames() throws Exception {
    LicenseStatusDTO dto =
        LicenseStatusDTO.from(buildStatus(LicenseManager.StatusCode.VALID, 20265L, 47L, 5));
    String json = JsonUtils.objectMapper().writeValueAsString(dto);

    Assertions.assertTrue(json.contains("\"issuedTo\""));
    Assertions.assertTrue(json.contains("\"licenseId\""));
    Assertions.assertTrue(json.contains("\"expiresAt\""));
    Assertions.assertTrue(json.contains("\"daysRemaining\""));
    Assertions.assertTrue(json.contains("\"gracePeriodDays\""));
    Assertions.assertTrue(json.contains("\"status\""));
    Assertions.assertTrue(json.contains("\"maxNodes\""));
    Assertions.assertTrue(json.contains("\"activeNodes\""));
  }
}
