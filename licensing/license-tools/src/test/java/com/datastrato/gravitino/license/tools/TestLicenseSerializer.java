/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.tools;

import com.datastrato.gravitino.license.LicensePayload;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestLicenseSerializer {

  @Test
  void testRoundTrip() {
    UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    byte[] payload = LicenseSerializer.serialize("Acme Corp", id, 19900L, 20265L, 30, (short) 5);
    LicensePayload parsed = LicensePayload.parse(payload);

    Assertions.assertEquals("Acme Corp", parsed.getIssuedTo());
    Assertions.assertEquals(id, parsed.getLicenseId());
    Assertions.assertEquals(19900L, parsed.getIssuedAtDays());
    Assertions.assertEquals(20265L, parsed.getExpiresAtDays());
    Assertions.assertEquals(30, parsed.getGracePeriodDays());
    Assertions.assertEquals(5, parsed.getMaxNodes());
  }

  @Test
  void testUnlimitedNodes() {
    byte[] payload = LicenseSerializer.serialize("X", UUID.randomUUID(), 1L, 2L, 0, (short) -1);
    LicensePayload parsed = LicensePayload.parse(payload);
    Assertions.assertEquals(-1, parsed.getMaxNodes());
  }

  @Test
  void testNullIssuedToThrows() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> LicenseSerializer.serialize(null, UUID.randomUUID(), 1L, 2L, 0, (short) 1));
  }

  @Test
  void testNullLicenseIdThrows() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> LicenseSerializer.serialize("Acme", null, 1L, 2L, 0, (short) 1));
  }

  @Test
  void testBlankIssuedToThrows() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> LicenseSerializer.serialize("   ", UUID.randomUUID(), 1L, 2L, 0, (short) 1));
  }
}
