/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import com.datastrato.gravitino.catalog.sqlserver.utils.SqlServerUtils;
import java.util.stream.Stream;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property-style tests for parentheses stripping and default value literal conversion. Validates
 * Properties 6 and 7 from the task list.
 */
public class TestSqlServerDefaultValueConverterProperty {

  private final SqlServerColumnDefaultValueConverter converter =
      new SqlServerColumnDefaultValueConverter();

  // --- Property 6: Parentheses stripping is idempotent and correct ---

  /** Provides strings with various layers of parentheses. */
  static Stream<Arguments> parenthesesStrippingCases() {
    return Stream.of(
        Arguments.of("value", "value"),
        Arguments.of("(value)", "value"),
        Arguments.of("((value))", "value"),
        Arguments.of("(((value)))", "value"),
        Arguments.of("((((value))))", "value"),
        Arguments.of("(((((value)))))", "value"),
        Arguments.of("((0))", "0"),
        Arguments.of("('hello')", "'hello'"),
        Arguments.of("(getdate())", "getdate()"),
        Arguments.of("(NULL)", "NULL"),
        Arguments.of("((42))", "42"));
  }

  @ParameterizedTest
  @MethodSource("parenthesesStrippingCases")
  void testParenthesesStrippingProducesExpectedResult(String input, String expected) {
    String result = SqlServerUtils.stripOuterParentheses(input);
    Assertions.assertEquals(expected, result);
  }

  @ParameterizedTest
  @MethodSource("parenthesesStrippingCases")
  void testParenthesesStrippingIsIdempotent(String input, String expected) {
    String first = SqlServerUtils.stripOuterParentheses(input);
    String second = SqlServerUtils.stripOuterParentheses(first);
    Assertions.assertEquals(first, second, "stripOuterParentheses should be idempotent");
  }

  @Test
  void testParenthesesStrippingNull() {
    Assertions.assertNull(SqlServerUtils.stripOuterParentheses(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "abc", "123", "no_parens"})
  void testParenthesesStrippingNoParens(String input) {
    Assertions.assertEquals(input.trim(), SqlServerUtils.stripOuterParentheses(input));
  }

  // --- Property 7: Default value literal conversion preserves value semantics ---

  @Test
  void testCurrentTimestampToGetdate() {
    String result = converter.fromGravitino(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP);
    Assertions.assertEquals("GETDATE()", result);
  }

  @Test
  void testNullLiteralConversion() {
    String result = converter.fromGravitino(Literals.NULL);
    Assertions.assertEquals("NULL", result);
  }

  /** Provides numeric literals for round-trip testing. */
  static Stream<Arguments> numericLiterals() {
    return Stream.of(
        Arguments.of(Literals.integerLiteral(0), "0"),
        Arguments.of(Literals.integerLiteral(42), "42"),
        Arguments.of(Literals.integerLiteral(-1), "-1"),
        Arguments.of(Literals.longLiteral(100L), "100"),
        Arguments.of(Literals.shortLiteral((short) 5), "5"),
        Arguments.of(Literals.floatLiteral(3.14f), "3.14"),
        Arguments.of(Literals.doubleLiteral(2.718), "2.718"));
  }

  @ParameterizedTest
  @MethodSource("numericLiterals")
  void testNumericFromGravitinoProducesValueAsIs(
      org.apache.gravitino.rel.expressions.literals.Literal<?> literal, String expected) {
    String result = converter.fromGravitino(literal);
    Assertions.assertEquals(expected, result);
  }

  /** Provides string literals for conversion testing. */
  static Stream<Arguments> stringLiterals() {
    return Stream.of(
        Arguments.of(Literals.stringLiteral("hello"), "'hello'"),
        Arguments.of(Literals.stringLiteral(""), "''"),
        Arguments.of(Literals.stringLiteral("test value"), "'test value'"));
  }

  @ParameterizedTest
  @MethodSource("stringLiterals")
  void testStringFromGravitinoProducesQuotedValue(
      org.apache.gravitino.rel.expressions.literals.Literal<?> literal, String expected) {
    String result = converter.fromGravitino(literal);
    Assertions.assertEquals(expected, result);
  }
}
