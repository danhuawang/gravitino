/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TestLicenseVerifier {

  private static final LicenseVerifier VERIFIER = TestKeyPairUtil.newVerifier();

  @Test
  void testValidKeyVerifies() throws Exception {
    byte[] payload = TestLicensePayload.buildTestPayload();
    String key = TestKeyPairUtil.buildKey(payload);
    LicensePayload result = VERIFIER.verify(key);
    Assertions.assertEquals("Acme Corp", result.getIssuedTo());
    Assertions.assertEquals(5, result.getMaxNodes());
  }

  @Test
  void testNullKeyThrows() {
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(null));
    Assertions.assertEquals(LicenseException.ErrorCode.MISSING, ex.getErrorCode());
  }

  @Test
  void testMissingPrefixThrows() {
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify("NOPFX-abc123"));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testCorruptBase64Throws() {
    LicenseException ex =
        Assertions.assertThrows(
            LicenseException.class, () -> VERIFIER.verify("GRAV-!!!notbase64!!!"));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testStandardBase64CharsThrow() throws Exception {
    // A key encoded with standard Base64 (+, /) instead of URL-safe (-, _) should be rejected.
    // getUrlDecoder() is strict about '+' and '/' so this produces INVALID_FORMAT.
    byte[] payload = TestLicensePayload.buildTestPayload();
    byte[] sig = TestKeyPairUtil.sign(payload);
    byte[] combined = new byte[payload.length + sig.length];
    System.arraycopy(payload, 0, combined, 0, payload.length);
    System.arraycopy(sig, 0, combined, payload.length, sig.length);
    // Force standard Base64 encoding (uses + and / instead of - and _)
    String standardEncoded = Base64.getEncoder().withoutPadding().encodeToString(combined);
    // No +/ chars in this particular payload — skip rather than produce a false pass
    Assumptions.assumeFalse(
        standardEncoded.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(combined)),
        "payload contains no +/ chars; skipping");
    String key = "GRAV-" + standardEncoded;
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testEmptyPayloadAfterPrefixThrows() {
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify("GRAV-"));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testTooShortToBeFindDerBoundaryThrows() {
    // A valid Base64url blob that is far too short to contain both a payload and DER signature
    String key = "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[10]);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  @Test
  void testTamperedPayloadThrows() throws Exception {
    byte[] payload = TestLicensePayload.buildTestPayload();
    String key = TestKeyPairUtil.buildKey(payload);
    char[] chars = key.toCharArray();
    // Flip a character deep in the payload region
    chars[10] = (chars[10] == 'A') ? 'B' : 'A';
    Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(new String(chars)));
  }

  @Test
  void testTamperedSignatureThrows() throws Exception {
    byte[] payload = TestLicensePayload.buildTestPayload();
    byte[] sig = TestKeyPairUtil.sign(payload);
    // Flip a byte in the middle of the signature
    sig[sig.length / 2] = (byte) (sig[sig.length / 2] ^ 0xFF);
    byte[] combined = new byte[payload.length + sig.length];
    System.arraycopy(payload, 0, combined, 0, payload.length);
    System.arraycopy(sig, 0, combined, payload.length, sig.length);
    String key = "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_SIGNATURE, ex.getErrorCode());
  }

  @Test
  void testWrongKeyPairThrows() throws Exception {
    // Build a key signed with a different keypair — verifier should reject it
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    LicenseVerifier wrongVerifier = new LicenseVerifier(gen.generateKeyPair().getPublic());
    byte[] payload = TestLicensePayload.buildTestPayload();
    String key = TestKeyPairUtil.buildKey(payload);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> wrongVerifier.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_SIGNATURE, ex.getErrorCode());
  }

  @Test
  void testUnknownVersionThrows() throws Exception {
    byte[] payload = TestLicensePayload.buildTestPayload();
    payload[0] = 99; // unsupported version
    String key = TestKeyPairUtil.buildKey(payload);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.UNKNOWN_VERSION, ex.getErrorCode());
  }

  @Test
  void testUnlimitedNodesEndToEnd() throws Exception {
    // maxNodes = 0xFFFF is the sentinel for unlimited; verify it round-trips through verify()
    byte[] payload = TestLicensePayload.buildTestPayload();
    payload[26] = (byte) 0xFF;
    payload[27] = (byte) 0xFF;
    String key = TestKeyPairUtil.buildKey(payload);
    LicensePayload result = VERIFIER.verify(key);
    Assertions.assertEquals(-1, result.getMaxNodes());
  }

  /**
   * Encodes just the payload bytes with no signature appended. decoded.length == 29 + nameLen ==
   * payloadEnd → the new "key truncated: no signature" guard.
   */
  @Test
  void testKeyWithNoSignatureBytesThrows() throws Exception {
    byte[] payload = TestLicensePayload.buildTestPayload(); // nameLen=9, payload length=38
    String key = "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_FORMAT, ex.getErrorCode());
  }

  /**
   * Incrementing nameLen by 1 in the combined bytes (without re-signing) shifts the split one byte
   * to the right, pulling one signature byte into the "payload". The signature verification fails,
   * which proves the split is driven by nameLen at byte 28, not a DER scan.
   */
  @Test
  void testNameLenDrivesSplitBoundary() throws Exception {
    byte[] payload = TestLicensePayload.buildTestPayload(); // nameLen=9 at byte 28
    byte[] sig = TestKeyPairUtil.sign(payload);
    byte[] combined = new byte[payload.length + sig.length];
    System.arraycopy(payload, 0, combined, 0, payload.length);
    System.arraycopy(sig, 0, combined, payload.length, sig.length);
    // Inflate nameLen: split shifts right by 1, pulling one signature byte into the payload region
    combined[28] = (byte) (combined[28] + 1);
    String key = "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_SIGNATURE, ex.getErrorCode());
  }

  /** A payload with a longer-than-default name round-trips through verify() correctly. */
  @Test
  void testLongNameRoundTrips() throws Exception {
    byte[] name = "Acme Corporation Ltd. Legal Dept".getBytes(StandardCharsets.UTF_8);
    byte[] base = TestLicensePayload.buildTestPayload();
    byte[] payload = new byte[29 + name.length];
    System.arraycopy(base, 0, payload, 0, 29);
    payload[28] = (byte) name.length;
    System.arraycopy(name, 0, payload, 29, name.length);
    String key = TestKeyPairUtil.buildKey(payload);
    LicensePayload result = VERIFIER.verify(key);
    Assertions.assertEquals(new String(name, StandardCharsets.UTF_8), result.getIssuedTo());
  }

  @Test
  void testTruncatedPayloadThrows() throws Exception {
    // Cut the payload short so the issuedTo name is missing.
    // The split uses nameLen from byte 28 (still 9), so the deterministic split at position 38
    // pulls 5 signature bytes into the "payload", causing signature verification to fail.
    // The key is still correctly rejected — INVALID_SIGNATURE is the expected outcome.
    byte[] full = TestLicensePayload.buildTestPayload();
    byte[] truncated = new byte[full.length - 5];
    System.arraycopy(full, 0, truncated, 0, truncated.length);
    String key = TestKeyPairUtil.buildKey(truncated);
    LicenseException ex =
        Assertions.assertThrows(LicenseException.class, () -> VERIFIER.verify(key));
    Assertions.assertEquals(LicenseException.ErrorCode.INVALID_SIGNATURE, ex.getErrorCode());
  }
}
