/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.junit.jupiter.api.Test;

public class TestOracleCatalogCapability {

  private final OracleCatalogCapability capability = new OracleCatalogCapability();

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
  void testSpecificationOnNameRejectsTooLongName() {
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "a" + "b".repeat(30));
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameAcceptsMaxLengthName() {
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "a" + "b".repeat(29));
    assertTrue(result.supported());
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
  void testSpecificationOnNameQuotedFormRejectsTooLongInner() {
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(31) + "\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormAcceptsMaxLengthInner() {
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "\"" + "a".repeat(30) + "\"");
    assertTrue(result.supported());
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
    // 11 three-byte UTF-8 characters = 33 bytes, exceeding Oracle's 30-byte identifier limit, even
    // though it is only 11 Java characters -- well under a naive character-count check.
    String elevenMultiByteChars = "测".repeat(11);
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "\"" + elevenMultiByteChars + "\"");
    assertFalse(result.supported());
  }

  @Test
  void testSpecificationOnNameQuotedFormAcceptsNameAtByteLengthLimit() {
    // 10 three-byte UTF-8 characters = exactly 30 bytes, the Oracle identifier length limit.
    String tenMultiByteChars = "测".repeat(10);
    CapabilityResult result =
        capability.specificationOnName(Capability.Scope.TABLE, "\"" + tenMultiByteChars + "\"");
    assertTrue(result.supported());
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
