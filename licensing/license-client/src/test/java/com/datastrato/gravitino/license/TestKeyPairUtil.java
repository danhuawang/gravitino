/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Test helper: generates an in-memory ECDSA P-256 key pair for signing test license payloads. */
public class TestKeyPairUtil {

  private static final KeyPair KEY_PAIR = generateKeyPair();

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
      gen.initialize(new ECGenParameterSpec("secp256r1"));
      return gen.generateKeyPair();
    } catch (Exception e) {
      throw new RuntimeException("Failed to generate test EC key pair", e);
    }
  }

  public static PublicKey getPublicKey() {
    return KEY_PAIR.getPublic();
  }

  public static LicenseVerifier newVerifier() {
    return new LicenseVerifier(KEY_PAIR.getPublic());
  }

  public static byte[] sign(byte[] payload) throws Exception {
    Signature sig = Signature.getInstance("SHA256withECDSA");
    sig.initSign((ECPrivateKey) KEY_PAIR.getPrivate());
    sig.update(payload);
    return sig.sign();
  }

  public static String buildKey(byte[] payload) throws Exception {
    byte[] sig = sign(payload);
    byte[] combined = new byte[payload.length + sig.length];
    System.arraycopy(payload, 0, combined, 0, payload.length);
    System.arraycopy(sig, 0, combined, payload.length, sig.length);
    return "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
  }
}
