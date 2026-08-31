/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * Represents the decoded payload embedded in a Gravitino license key.
 *
 * <p>The raw payload is a compact binary structure encoded in big-endian byte order:
 *
 * <pre>
 * Offset  Len  Type      Field
 * ------  ---  --------  ------------------------------------------
 *      0    1  uint8     version         – format version (currently 1)
 *      1    8  int64     licenseId MSB   – most-significant bits of the license UUID
 *      9    8  int64     licenseId LSB   – least-significant bits of the license UUID
 *     17    4  uint32    issuedAtDays    – issue date as days since the Unix epoch
 *     21    4  uint32    expiresAtDays   – expiry date as days since the Unix epoch
 *     25    1  uint8     gracePeriodDays – extra days allowed after expiry (0 = no grace)
 *     26    2  uint16    maxNodes        – maximum cluster nodes allowed (0xFFFF = unlimited)
 *     28    1  uint8     nameLen         – byte length of the issuedTo string that follows
 *     29    N  UTF-8     issuedTo        – name of the licensee (N = nameLen, max 255 bytes)
 * </pre>
 *
 * <p>The total minimum payload length is 30 bytes (29-byte header + at least 1 byte for issuedTo).
 * This binary blob is what gets signed by the license authority; the signature is verified by
 * {@link LicenseVerifier} before this class is instantiated.
 */
public class LicensePayload {

  public static final int CURRENT_VERSION = 1;

  private final int version;
  private final UUID licenseId;
  private final long issuedAtDays;
  private final long expiresAtDays;
  private final int gracePeriodDays;
  private final int maxNodes;
  private final String issuedTo;

  private LicensePayload(
      int version,
      UUID licenseId,
      long issuedAtDays,
      long expiresAtDays,
      int gracePeriodDays,
      int maxNodes,
      String issuedTo) {
    this.version = version;
    this.licenseId = licenseId;
    this.issuedAtDays = issuedAtDays;
    this.expiresAtDays = expiresAtDays;
    this.gracePeriodDays = gracePeriodDays;
    this.maxNodes = maxNodes;
    this.issuedTo = issuedTo;
  }

  public static LicensePayload parse(byte[] data) {
    if (data.length < 30) {
      throw new LicenseException(LicenseException.ErrorCode.INVALID_FORMAT, "payload too short");
    }
    int version = data[0] & 0xFF;
    if (version != CURRENT_VERSION) {
      throw new LicenseException(
          LicenseException.ErrorCode.UNKNOWN_VERSION, "unrecognized version " + version);
    }

    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    long msb = buf.getLong(1);
    long lsb = buf.getLong(9);
    UUID licenseId = new UUID(msb, lsb);

    long issuedAtDays = buf.getInt(17) & 0xFFFFFFFFL;
    long expiresAtDays = buf.getInt(21) & 0xFFFFFFFFL;
    int gracePeriodDays = data[25] & 0xFF;
    int maxNodesRaw = buf.getShort(26) & 0xFFFF;
    int maxNodes = (maxNodesRaw == 0xFFFF) ? -1 : maxNodesRaw;
    int nameLen = data[28] & 0xFF;

    if (data.length < 29 + nameLen) {
      throw new LicenseException(LicenseException.ErrorCode.INVALID_FORMAT, "payload truncated");
    }
    String issuedTo = new String(data, 29, nameLen, StandardCharsets.UTF_8);

    validate(issuedAtDays, expiresAtDays, maxNodes, issuedTo);
    return new LicensePayload(
        version, licenseId, issuedAtDays, expiresAtDays, gracePeriodDays, maxNodes, issuedTo);
  }

  public int getVersion() {
    return version;
  }

  public UUID getLicenseId() {
    return licenseId;
  }

  public long getIssuedAtDays() {
    return issuedAtDays;
  }

  public long getExpiresAtDays() {
    return expiresAtDays;
  }

  public int getGracePeriodDays() {
    return gracePeriodDays;
  }

  public int getMaxNodes() {
    return maxNodes;
  }

  public String getIssuedTo() {
    return issuedTo;
  }

  private static void validate(
      long issuedAtDays, long expiresAtDays, int maxNodes, String issuedTo) {
    if (issuedAtDays <= 0) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_FORMAT, "issuedAtDays must be > 0");
    }
    if (expiresAtDays <= issuedAtDays) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_FORMAT, "expiresAtDays must be after issuedAtDays");
    }
    if (maxNodes != -1 && maxNodes <= 0) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_FORMAT,
          "maxNodes must be -1 (unlimited) or > 0, got " + maxNodes);
    }
    if (StringUtils.isBlank(issuedTo)) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_FORMAT, "issuedTo must not be blank");
    }
  }
}
