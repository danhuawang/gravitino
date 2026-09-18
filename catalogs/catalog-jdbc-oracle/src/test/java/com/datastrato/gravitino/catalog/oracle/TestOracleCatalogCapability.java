/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.junit.jupiter.api.Test;

public class TestOracleCatalogCapability {

  // Default capability: no server-resolved limit, so it uses the modern 128-byte limit.
  private final OracleCatalogCapability capability = new OracleCatalogCapability();

  // Capability pinned to the pre-12.2 30-byte limit, used to test length-boundary behavior for an
  // older Oracle server.
  private final OracleCatalogCapability legacyCapability = new OracleCatalogCapability(30);

  @Test
  void testCaseSensitiveOnNameIsUnsupportedForAllScopes() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      CapabilityResult result = capability.caseSensitiveOnName(scope);
      assertFalse(result.supported(), "Expected case-insensitive for scope " + scope);
    }
  }

  @Test
  void testSpecificationOnNameAcceptsValidLowercaseName() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "valid_name");
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameAcceptsValidUppercaseName() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "VALID_NAME");
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameRejectsNameStartingWithDigit() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "1invalid");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameRejectsTooLongNameForLegacyLimit() {
    // 31 chars exceeds the pre-12.2 30-byte limit.
    CapabilityResult result =
        legacyCapability.specificationOnName(Capability.Scope.TABLE, "a" + "b".repeat(30));
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameAcceptsMaxLengthNameForLegacyLimit() {
    // 30 chars is exactly the pre-12.2 limit.
    CapabilityResult result =
        legacyCapability.specificationOnName(Capability.Scope.TABLE, "a" + "b".repeat(29));
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameAcceptsNameOver30ForDefaultLimit() {
    // A 31-character name is rejected by a legacy (30) limit but accepted by the default 128-byte
    // limit that modern Oracle (12.2+) allows -- the fix for issue #2095.
    String name = "a" + "b".repeat(30);
    assertFalse(legacyCapability.specificationOnName(Capability.Scope.TABLE, name).supported());
    assertTrue(capability.specificationOnName(Capability.Scope.TABLE, name).supported());
  }

  @Test
  void testSpecificationOnNameAcceptsMaxLengthNameForDefaultLimit() {
    // 128 chars is exactly the default (12.2+) limit.
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "a" + "b".repeat(127));
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameRejectsNameOver128ForDefaultLimit() {
    // 129 chars exceeds even the modern 128-byte limit.
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "a" + "b".repeat(128));
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormAcceptsArbitraryCaseAndSpecialChars() {
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "\"My Table!\"");
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormRejectsEmptyInner() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "\"\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormRejectsTooLongInnerForLegacyLimit() {
    CapabilityResult result =
        legacyCapability.specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(31) + "\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormAcceptsMaxLengthInnerForLegacyLimit() {
    CapabilityResult result =
        legacyCapability.specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(30) + "\"");
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormAcceptsLongInnerForDefaultLimit() {
    // A 31-char quoted inner is rejected by the legacy limit but accepted by the default 128-byte
    // limit; 128 is exactly the boundary and 129 is rejected.
    assertFalse(
        legacyCapability
            .specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(31) + "\"")
            .supported());
    assertTrue(
        capability
            .specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(128) + "\"")
            .supported());
    assertFalse(
        capability
            .specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(129) + "\"")
            .supported());
  }

  @Test
  void testSpecificationOnNameRejectsMalformedQuoting() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "\"A\"B\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormRejectsEmbeddedPeriod() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "\"A.B\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormRejectsEmbeddedDoubleQuote() {
    // Oracle has no "" escape for an embedded double quote in a quoted identifier (ORA-25716: the
    // identifier contains a double quotation mark), so this must be rejected.
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "\"A\"\"B\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormRejectsEmbeddedNul() {
    // Oracle does not allow a NUL character in an identifier.
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "\"A" + '\0' + "B\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormRejectsNameExceedingByteLength() {
    // 11 three-byte UTF-8 characters = 33 bytes, exceeding the pre-12.2 30-byte limit, even though
    // it is only 11 Java characters -- well under a naive character-count check.
    String elevenMultiByteChars = "测".repeat(11);
    CapabilityResult result =
        legacyCapability.specificationOnName(
            Capability.Scope.TABLE, "\"" + elevenMultiByteChars + "\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormAcceptsNameAtByteLengthLimit() {
    // 10 three-byte UTF-8 characters = exactly 30 bytes, the pre-12.2 identifier length limit.
    String tenMultiByteChars = "测".repeat(10);
    CapabilityResult result =
        legacyCapability.specificationOnName(
            Capability.Scope.TABLE, "\"" + tenMultiByteChars + "\"");
    assertTrue(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormEnforcesByteLengthAgainstDefaultLimit() {
    // 43 three-byte UTF-8 characters = 129 bytes, exceeding the default 128-byte limit, while 42
    // characters = 126 bytes is accepted.
    assertFalse(
        capability
            .specificationOnName(Capability.Scope.TABLE, "\"" + "测".repeat(43) + "\"")
            .supported());
    assertTrue(
        capability
            .specificationOnName(Capability.Scope.TABLE, "\"" + "测".repeat(42) + "\"")
            .supported());
  }

  @Test
  void testNormalizeNameUppercasesUnquotedInput() {
    assertEquals("FOO", capability.normalizeName(Capability.Scope.TABLE, "foo"));
    assertEquals("FOO", capability.normalizeName(Capability.Scope.TABLE, "FOO"));
    assertEquals("FOO", capability.normalizeName(Capability.Scope.TABLE, "Foo"));
  }

  @Test
  void testNormalizeNameUnquotesQuotedInputPreservingCase() {
    // The returned name never itself contains a literal quote character: quoting is a one-time
    // signal evaluated here, not something persisted in the normalized name.
    assertEquals("MyTable", capability.normalizeName(Capability.Scope.TABLE, "\"MyTable\""));
  }

  @Test
  void testNormalizeNameCanonicalizesQuotedUppercaseGrammarValidToBareForm() {
    // "FOO" and FOO address the same Oracle object, so both must normalize to the same string.
    assertEquals("FOO", capability.normalizeName(Capability.Scope.TABLE, "\"FOO\""));
  }

  @Test
  void testNormalizeNamePreservesQuotedContentEvenWhenGrammarInvalid() {
    // "FOO BAR" could never have been created unquoted (embedded space), but the result is still a
    // bare string with its case preserved, never re-wrapped in quotes.
    assertEquals("FOO BAR", capability.normalizeName(Capability.Scope.TABLE, "\"FOO BAR\""));
  }
}
