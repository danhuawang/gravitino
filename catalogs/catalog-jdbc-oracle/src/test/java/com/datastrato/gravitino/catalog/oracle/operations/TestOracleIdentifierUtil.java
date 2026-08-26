/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestOracleIdentifierUtil {

  @Test
  void testIsQuoted() {
    assertTrue(OracleIdentifierUtil.isQuoted("\"Foo\""));
    assertTrue(OracleIdentifierUtil.isQuoted("\"\""));
    assertFalse(OracleIdentifierUtil.isQuoted("Foo"));
    assertFalse(OracleIdentifierUtil.isQuoted("\""));
    assertFalse(OracleIdentifierUtil.isQuoted(""));
  }

  @Test
  void testUnquoteStripsQuotesAndPreservesCase() {
    assertEquals("MyTable", OracleIdentifierUtil.unquote("\"MyTable\""));
    assertEquals("foo", OracleIdentifierUtil.unquote("\"foo\""));
  }

  @Test
  void testUnquoteRejectsEmbeddedDoubleQuote() {
    // Oracle has no "" escape for an embedded double quote in a quoted identifier (it rejects it
    // with ORA-25716), so a doubled quote must be rejected here too, not silently unescaped.
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.unquote("\"A\"\"B\""));
  }

  @Test
  void testUnquoteRejectsNonQuotedInput() {
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.unquote("Foo"));
  }

  @Test
  void testUnquoteRejectsMalformedQuoting() {
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.unquote("\"A\"B\""));
  }

  @Test
  void testQuote() {
    assertEquals("\"Foo\"", OracleIdentifierUtil.quote("Foo"));
  }

  @Test
  void testQuoteRejectsEmbeddedDoubleQuote() {
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.quote("A\"B"));
  }

  @Test
  void testToPhysicalNameUnquotedUppercasesAllVariants() {
    assertEquals("FOO", OracleIdentifierUtil.toPhysicalName("foo"));
    assertEquals("FOO", OracleIdentifierUtil.toPhysicalName("FOO"));
    assertEquals("FOO", OracleIdentifierUtil.toPhysicalName("Foo"));
  }

  @Test
  void testToPhysicalNameQuotedPreservesCaseAndStripsQuotes() {
    assertEquals("MyTable", OracleIdentifierUtil.toPhysicalName("\"MyTable\""));
  }

  @Test
  void testToPhysicalNamePreservesWhitespaceOnlyQuotedContent() {
    // A quoted Oracle identifier can be made up entirely of spaces; toPhysicalName must preserve
    // that whitespace instead of rejecting it as blank.
    assertEquals("   ", OracleIdentifierUtil.toPhysicalName("\"   \""));
  }

  @Test
  void testToPhysicalNameRejectsEmbeddedDoubleQuote() {
    assertThrows(
        IllegalArgumentException.class, () -> OracleIdentifierUtil.toPhysicalName("\"A\"\"B\""));
  }

  @Test
  void testToPhysicalNameMalformedQuotingThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> OracleIdentifierUtil.toPhysicalName("\"A\"B\""));
  }

  @Test
  void testToPhysicalNameRejectsNullOrEmpty() {
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.toPhysicalName(null));
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.toPhysicalName(""));
  }

  @Test
  void testQuotedNameUnquotedInputUnchangedBehavior() {
    assertEquals("\"FOO\"", OracleIdentifierUtil.quotedName("foo"));
    assertEquals("\"FOO\"", OracleIdentifierUtil.quotedName("FOO"));
  }

  @Test
  void testQuotedNameQuotedInputReQuotesConsistently() {
    assertEquals("\"MyTable\"", OracleIdentifierUtil.quotedName("\"MyTable\""));
  }

  @Test
  void testQuotedNameRejectsEmbeddedDoubleQuote() {
    // Index and partition names are not validated against ORACLE_NAME_PATTERN, so this is the only
    // guard against generating SQL Oracle would reject for a name containing a double quote.
    assertThrows(IllegalArgumentException.class, () -> OracleIdentifierUtil.quotedName("a\"b"));
  }
}
