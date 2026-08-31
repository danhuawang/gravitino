/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.apache.commons.lang3.StringUtils;

public class LicenseVerifier {

  private static final String PREFIX = "GRAV-";
  private static final String PUBLIC_KEY_RESOURCE = "/gravitino-master.pub";

  private final PublicKey publicKey;

  public LicenseVerifier(PublicKey publicKey) {
    this.publicKey = publicKey;
  }

  /** Production factory: loads the embedded public key from the classpath resource. */
  public static LicenseVerifier fromClasspath() {
    try {
      return new LicenseVerifier(loadPublicKey());
    } catch (Exception e) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_SIGNATURE,
          "failed to load public key: " + e.getMessage(),
          e);
    }
  }

  public LicensePayload verify(String keyString) {
    if (StringUtils.isBlank(keyString)) {
      throw new LicenseException(
          LicenseException.ErrorCode.MISSING, "license key is missing or blank");
    }
    if (!keyString.startsWith(PREFIX)) {
      throw new LicenseException(LicenseException.ErrorCode.INVALID_FORMAT, "missing GRAV- prefix");
    }

    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(keyString.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_FORMAT, "invalid Base64url encoding");
    }

    if (decoded.length < 30) {
      throw new LicenseException(LicenseException.ErrorCode.INVALID_FORMAT, "key too short");
    }
    // The payload format encodes nameLen at byte 28, so the payload boundary is deterministic.
    int nameLen = decoded[28] & 0xFF;
    int payloadEnd = 29 + nameLen;
    if (decoded.length <= payloadEnd) {
      throw new LicenseException(
          LicenseException.ErrorCode.INVALID_FORMAT, "key truncated: no signature");
    }

    byte[] payload = new byte[payloadEnd];
    byte[] signature = new byte[decoded.length - payloadEnd];
    System.arraycopy(decoded, 0, payload, 0, payloadEnd);
    System.arraycopy(decoded, payloadEnd, signature, 0, signature.length);

    verifySignature(payload, signature);
    return LicensePayload.parse(payload);
  }

  private void verifySignature(byte[] payload, byte[] signature) {
    try {
      Signature sig = Signature.getInstance("SHA256withECDSA");
      sig.initVerify(publicKey);
      sig.update(payload);
      if (!sig.verify(signature)) {
        throw new LicenseException(LicenseException.ErrorCode.INVALID_SIGNATURE);
      }
    } catch (LicenseException e) {
      throw e;
    } catch (Exception e) {
      throw new LicenseException(LicenseException.ErrorCode.INVALID_SIGNATURE, e.getMessage(), e);
    }
  }

  private static PublicKey loadPublicKey() throws Exception {
    try (InputStream in = LicenseVerifier.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("gravitino-master.pub not found in classpath");
      }
      String pem =
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      byte[] keyBytes = Base64.getDecoder().decode(pem);
      return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
    }
  }
}
