/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.tools;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class LicenseSerializer {

  public static byte[] serialize(
      String issuedTo,
      UUID licenseId,
      long issuedAtDays,
      long expiresAtDays,
      int gracePeriodDays,
      short maxNodes) {
    if (issuedTo == null) {
      throw new IllegalArgumentException("issuedTo must not be null");
    }
    if (issuedTo.isBlank()) {
      throw new IllegalArgumentException("issuedTo must not be blank");
    }
    if (licenseId == null) {
      throw new IllegalArgumentException("licenseId must not be null");
    }
    byte[] nameBytes = issuedTo.getBytes(StandardCharsets.UTF_8);
    if (nameBytes.length > 255) {
      throw new IllegalArgumentException("issuedTo exceeds 255 bytes in UTF-8");
    }
    byte[] payload = new byte[29 + nameBytes.length];
    payload[0] = 1; // version

    long msb = licenseId.getMostSignificantBits();
    long lsb = licenseId.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      payload[1 + i] = (byte) (msb >>> (56 - 8 * i));
      payload[9 + i] = (byte) (lsb >>> (56 - 8 * i));
    }

    putUint32BE(payload, 17, issuedAtDays);
    putUint32BE(payload, 21, expiresAtDays);
    payload[25] = (byte) gracePeriodDays;
    payload[26] = (byte) (maxNodes >> 8);
    payload[27] = (byte) maxNodes;
    payload[28] = (byte) nameBytes.length;
    System.arraycopy(nameBytes, 0, payload, 29, nameBytes.length);
    return payload;
  }

  private static void putUint32BE(byte[] data, int off, long value) {
    data[off] = (byte) (value >> 24);
    data[off + 1] = (byte) (value >> 16);
    data[off + 2] = (byte) (value >> 8);
    data[off + 3] = (byte) value;
  }
}
