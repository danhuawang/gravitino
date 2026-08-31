/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestLicensePayload {

  // Hand-crafted payload for "Acme Corp":
  // version=1, licenseId=550e8400-e29b-41d4-a716-446655440000,
  // issuedAt=19900 (2024-06-10), expiresAt=20265 (2025-06-10),
  // gracePeriodDays=30, maxNodes=5, issuedTo="Acme Corp"
  static byte[] buildTestPayload() {
    byte[] name = "Acme Corp".getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[29 + name.length];
    payload[0] = LicensePayload.CURRENT_VERSION; // version

    // licenseId: 550e8400-e29b-41d4-a716-446655440000
    UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    long msb = id.getMostSignificantBits();
    long lsb = id.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      payload[1 + i] = (byte) (msb >>> (56 - 8 * i));
    }
    for (int i = 0; i < 8; i++) {
      payload[9 + i] = (byte) (lsb >>> (56 - 8 * i));
    }

    // issuedAt = 19900 (0x00004DBC)
    payload[17] = 0x00;
    payload[18] = 0x00;
    payload[19] = 0x4D;
    payload[20] = (byte) 0xBC;
    // expiresAt = 20265 (0x00004F29)
    payload[21] = 0x00;
    payload[22] = 0x00;
    payload[23] = 0x4F;
    payload[24] = 0x29;

    payload[25] = 30; // gracePeriodDays
    payload[26] = 0x00; // maxNodes high byte
    payload[27] = 0x05; // maxNodes low byte = 5
    payload[28] = (byte) name.length;
    System.arraycopy(name, 0, payload, 29, name.length);
    return payload;
  }

  @Test
  void testRoundTrip() {
    byte[] raw = buildTestPayload();
    LicensePayload p = LicensePayload.parse(raw);

    Assertions.assertEquals(LicensePayload.CURRENT_VERSION, p.getVersion());
    Assertions.assertEquals(
        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), p.getLicenseId());
    Assertions.assertEquals(19900L, p.getIssuedAtDays());
    Assertions.assertEquals(20265L, p.getExpiresAtDays());
    Assertions.assertEquals(30, p.getGracePeriodDays());
    Assertions.assertEquals(5, p.getMaxNodes());
    Assertions.assertEquals("Acme Corp", p.getIssuedTo());
  }

  @Test
  void testMaxNodesUnlimited() {
    byte[] raw = buildTestPayload();
    raw[26] = (byte) 0xFF;
    raw[27] = (byte) 0xFF;
    LicensePayload p = LicensePayload.parse(raw);
    Assertions.assertEquals(-1, p.getMaxNodes());
  }

  @Test
  void testMaxNodesAbove32767ParsedAsUnsigned() {
    // 0x8000 = 32768: would be negative (-32768) if parsed as a signed short.
    // Verify it is correctly parsed as the unsigned value 32768.
    byte[] raw = buildTestPayload();
    raw[26] = (byte) 0x80;
    raw[27] = 0x00;
    LicensePayload p = LicensePayload.parse(raw);
    Assertions.assertEquals(32768, p.getMaxNodes());
  }

  @Test
  void testMaxNodesJustBelowSentinel() {
    // 0xFFFE = 65534: just below the unlimited sentinel (0xFFFF = -1).
    // Must be treated as a concrete node limit of 65534, not as unlimited.
    byte[] raw = buildTestPayload();
    raw[26] = (byte) 0xFF;
    raw[27] = (byte) 0xFE;
    LicensePayload p = LicensePayload.parse(raw);
    Assertions.assertEquals(65534, p.getMaxNodes());
  }

  @Test
  void testMaxNodesLargeValue() {
    // 0x9C40 = 40000: typical large unsigned value that would overflow a signed short.
    byte[] raw = buildTestPayload();
    raw[26] = (byte) 0x9C;
    raw[27] = (byte) 0x40;
    LicensePayload p = LicensePayload.parse(raw);
    Assertions.assertEquals(40000, p.getMaxNodes());
  }

  @Test
  void testBigEndianByteOrder() {
    // Explicitly verify that the most-significant byte comes first for multi-byte fields.
    byte[] raw = buildTestPayload();

    // issuedAtDays: set bytes 17-20 to 0x00_01_02_03 → expected value 0x00010203 = 66051
    raw[17] = 0x00;
    raw[18] = 0x01;
    raw[19] = 0x02;
    raw[20] = 0x03;
    // expiresAtDays: set bytes 21-24 to 0x00_01_02_04 → expected value 0x00010204 = 66052
    raw[21] = 0x00;
    raw[22] = 0x01;
    raw[23] = 0x02;
    raw[24] = 0x04;
    // maxNodes: set bytes 26-27 to 0x00_0A → expected value 10
    raw[26] = 0x00;
    raw[27] = 0x0A;

    LicensePayload p = LicensePayload.parse(raw);
    Assertions.assertEquals(66051L, p.getIssuedAtDays());
    Assertions.assertEquals(66052L, p.getExpiresAtDays());
    Assertions.assertEquals(10, p.getMaxNodes());
  }

  @Test
  void testTooShortThrows() {
    Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(new byte[10]));
  }

  @Test
  void testUnknownVersionThrows() {
    byte[] raw = buildTestPayload();
    raw[0] = 99;
    Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(raw));
  }

  @Test
  void testIssuedAtDaysZeroThrows() {
    byte[] raw = buildTestPayload();
    // Set issuedAtDays = 0
    raw[17] = raw[18] = raw[19] = raw[20] = 0;
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(raw));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testExpiresAtBeforeIssuedAtThrows() {
    byte[] raw = buildTestPayload();
    // Set expiresAt = issuedAt - 1 (19899 = 0x00004DBB)
    raw[21] = 0x00;
    raw[22] = 0x00;
    raw[23] = 0x4D;
    raw[24] = (byte) 0xBB;
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(raw));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testExpiresAtEqualToIssuedAtThrows() {
    byte[] raw = buildTestPayload();
    // Set expiresAt = issuedAt (19900)
    raw[21] = raw[17];
    raw[22] = raw[18];
    raw[23] = raw[19];
    raw[24] = raw[20];
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(raw));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testMaxNodesZeroThrows() {
    byte[] raw = buildTestPayload();
    raw[26] = 0x00;
    raw[27] = 0x00; // maxNodes = 0
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(raw));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testBlankIssuedToThrows() {
    byte[] name = "   ".getBytes(StandardCharsets.UTF_8);
    byte[] raw = buildTestPayload();
    // Replace issuedTo with blank spaces
    raw[28] = (byte) name.length;
    byte[] extended = new byte[29 + name.length];
    System.arraycopy(raw, 0, extended, 0, 29);
    System.arraycopy(name, 0, extended, 29, name.length);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(extended));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testEmptyIssuedToThrows() {
    byte[] raw = buildTestPayload();
    raw[28] = 0x00; // nameLen = 0, issuedTo = ""
    byte[] trimmed = new byte[29];
    System.arraycopy(raw, 0, trimmed, 0, 29);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> LicensePayload.parse(trimmed));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }
}
