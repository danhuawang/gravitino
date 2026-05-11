/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.tools;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestSignCommand {

  private static SignCommand.Signer newSigner() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair kp = gen.generateKeyPair();
    return payload -> {
      Signature sig = Signature.getInstance("SHA256withECDSA");
      sig.initSign((ECPrivateKey) kp.getPrivate());
      sig.update(payload);
      return sig.sign();
    };
  }

  @Test
  void testSignProducesGravKeyString() throws Exception {
    String key = new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 30, -1);
    Assertions.assertTrue(key.startsWith("GRAV-"), "key must start with GRAV-");
    Assertions.assertTrue(key.length() > 100, "key must be at least 100 chars");
  }

  @Test
  void testBlankIssuedToThrows() throws Exception {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "  ", "2030-01-01", 30, -1));
  }

  @Test
  void testIssuedToTooLongThrows() throws Exception {
    String longName = "A".repeat(256);
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), longName, "2030-01-01", 30, -1));
  }

  @Test
  void testExpiresTodayThrows() throws Exception {
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", today, 30, -1));
  }

  @Test
  void testNegativeGraceDaysThrows() throws Exception {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", -1, -1));
  }

  @Test
  void testGraceDaysTooLargeThrows() throws Exception {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 256, -1));
  }

  @Test
  void testMaxNodesZeroThrows() throws Exception {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 30, 0));
  }

  @Test
  void testMaxNodesNegativeNotMinusOneThrows() throws Exception {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 30, -2));
  }

  @Test
  void testMaxNodesTooLargeThrows() throws Exception {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 30, 65535));
  }

  @Test
  void testMaxNodesUnlimitedAndExplicitValid() throws Exception {
    // -1 (unlimited) and a concrete positive value must both succeed
    Assertions.assertDoesNotThrow(
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 30, -1));
    Assertions.assertDoesNotThrow(
        () -> new SignCommand().buildKeyString(newSigner(), "Test Corp", "2030-01-01", 30, 10));
  }
}
