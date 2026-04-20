/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

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
    if (keyString == null) {
      throw new LicenseException(LicenseException.ErrorCode.MISSING, "license key is null");
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

    int payloadEnd = findDerSignatureStart(decoded);
    byte[] payload = new byte[payloadEnd];
    byte[] signature = new byte[decoded.length - payloadEnd];
    System.arraycopy(decoded, 0, payload, 0, payloadEnd);
    System.arraycopy(decoded, payloadEnd, signature, 0, signature.length);

    verifySignature(payload, signature);
    return LicensePayload.parse(payload);
  }

  private int findDerSignatureStart(byte[] data) {
    // ECDSA P-256 DER signature starts with 0x30 <len>; scan backwards to find boundary.
    // Limitation: if the payload itself contains a byte sequence 0x30 <len> that satisfies
    // the boundary condition, the wrong split point may be chosen, causing INVALID_SIGNATURE.
    // This is an inherent trade-off of not encoding the boundary explicitly in the wire format.
    for (int i = data.length - 2; i >= 29; i--) {
      if ((data[i] & 0xFF) == 0x30) {
        int sigBodyLen = data[i + 1] & 0xFF;
        if (sigBodyLen < 0x80 && i + 2 + sigBodyLen == data.length) {
          return i;
        }
      }
    }
    throw new LicenseException(
        LicenseException.ErrorCode.INVALID_FORMAT, "cannot locate DER signature boundary");
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
