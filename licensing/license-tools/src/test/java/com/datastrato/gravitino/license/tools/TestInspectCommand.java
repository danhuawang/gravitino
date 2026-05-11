/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.tools;

import com.datastrato.gravitino.license.LicenseException;
import com.datastrato.gravitino.license.LicenseVerifier;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestInspectCommand {

  private static KeyPair keyPair;
  private static SignCommand.Signer signer;
  private static LicenseVerifier verifier;

  @BeforeAll
  static void setup() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    keyPair = gen.generateKeyPair();
    signer =
        payload -> {
          Signature sig = Signature.getInstance("SHA256withECDSA");
          sig.initSign((ECPrivateKey) keyPair.getPrivate());
          sig.update(payload);
          return sig.sign();
        };
    verifier = new LicenseVerifier(keyPair.getPublic());
  }

  @Test
  void testExecuteValidStatus() throws Exception {
    long today = today();
    String key = buildRawKey(today - 10, today + 365, 30, -1);
    String output = runExecute(key);
    Assertions.assertTrue(output.contains("Status      : VALID"), output);
    Assertions.assertTrue(output.contains("days remaining"), output);
  }

  @Test
  void testExecuteInGracePeriod() throws Exception {
    long today = today();
    String key = buildRawKey(today - 40, today - 5, 30, -1);
    String output = runExecute(key);
    Assertions.assertTrue(output.contains("Status      : IN_GRACE_PERIOD"), output);
    Assertions.assertTrue(output.contains("grace days remaining"), output);
  }

  @Test
  void testExecuteExpired() throws Exception {
    long today = today();
    String key = buildRawKey(today - 100, today - 40, 30, -1);
    String output = runExecute(key);
    Assertions.assertTrue(output.contains("Status      : EXPIRED"), output);
    Assertions.assertTrue(output.contains("days past grace period"), output);
  }

  @Test
  void testExecuteShowsFields() throws Exception {
    long today = today();
    String key = buildRawKey(today - 1, today + 365, 30, 10);
    String output = runExecute(key);
    Assertions.assertTrue(output.contains("Issued to   : Test Corp"), output);
    Assertions.assertTrue(output.contains("Max nodes   : 10"), output);
    Assertions.assertTrue(output.contains("Signature   : VALID"), output);
  }

  @Test
  void testExecuteUnlimitedNodes() throws Exception {
    long today = today();
    String key = buildRawKey(today - 1, today + 365, 30, -1);
    String output = runExecute(key);
    Assertions.assertTrue(output.contains("Max nodes   : unlimited"), output);
  }

  @Test
  void testTooShortKeyThrows() throws Exception {
    LicenseException ex =
        Assertions.assertThrows(
            LicenseException.class, () -> verifier.verify("GRAV-aGVsbG93b3JsZA"));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testMalformedKeyThrows() throws Exception {
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> verifier.verify("NOT-A-GRAV-KEY"));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testMissingKeyThrows() throws Exception {
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> verifier.verify(""));
    Assertions.assertEquals(LicenseException.ErrorCode.MISSING, ex.getErrorCode());
  }

  /** Builds a raw key bypassing SignCommand validation, allowing past expiry dates. */
  private String buildRawKey(long issuedAtDays, long expiresAtDays, int graceDays, int maxNodes)
      throws Exception {
    byte[] payload =
        LicenseSerializer.serialize(
            "Test Corp",
            UUID.randomUUID(),
            issuedAtDays,
            expiresAtDays,
            graceDays,
            (short) maxNodes);
    byte[] sig = signer.sign(payload);
    byte[] combined = new byte[payload.length + sig.length];
    System.arraycopy(payload, 0, combined, 0, payload.length);
    System.arraycopy(sig, 0, combined, payload.length, sig.length);
    return "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
  }

  private String runExecute(String key) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream saved = System.out;
    System.setOut(new PrintStream(baos));
    try {
      new InspectCommand(verifier).execute(new String[] {"--key", key});
    } finally {
      System.setOut(saved);
    }
    return baos.toString(StandardCharsets.UTF_8);
  }

  private static long today() {
    return LocalDate.now(ZoneOffset.UTC).toEpochDay();
  }
}
