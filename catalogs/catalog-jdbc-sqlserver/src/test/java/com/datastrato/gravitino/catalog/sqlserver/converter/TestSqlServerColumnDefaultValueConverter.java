/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for SqlServerColumnDefaultValueConverter. */
public class TestSqlServerColumnDefaultValueConverter {

  private final SqlServerColumnDefaultValueConverter converter =
      new SqlServerColumnDefaultValueConverter();

  @Test
  void testNullDefaultValueNullable() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("int");
    Expression result = converter.toGravitino(type, null, false, true);
    Assertions.assertEquals(DEFAULT_VALUE_NOT_SET, result);
  }

  @Test
  void testNullDefaultValueNotNullable() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("int");
    Expression result = converter.toGravitino(type, null, false, false);
    Assertions.assertEquals(DEFAULT_VALUE_NOT_SET, result);
  }

  @Test
  void testParenthesesStrippingNumeric() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("int");
    Expression result = converter.toGravitino(type, "((0))", false, false);
    Assertions.assertEquals(Literals.integerLiteral(0), result);
  }

  @Test
  void testParenthesesStrippingString() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("nvarchar");
    Expression result = converter.toGravitino(type, "('hello')", false, false);
    Assertions.assertEquals(Literals.stringLiteral("hello"), result);
  }

  @Test
  void testBoundedNvarcharLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("nvarchar");
    type.setColumnSize(50);
    Expression result = converter.toGravitino(type, "('hello')", false, false);
    Assertions.assertEquals(Literals.varcharLiteral(50, "hello"), result);
  }

  @ParameterizedTest
  @CsvSource({"10,2,12.50", "38,0,123", "18,0,42", "5,2,0.25"})
  void testNumericDefaultMatchesDecimal(int precision, int scale, String value) {
    for (String typeName : new String[] {"decimal", "numeric", "NUMERIC"}) {
      JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean(typeName);
      type.setColumnSize(precision);
      type.setScale(scale);
      Expression result = converter.toGravitino(type, "((" + value + "))", false, true);
      Assertions.assertEquals(Literals.decimalLiteral(Decimal.of(value, precision, scale)), result);
      Assertions.assertEquals(value, converter.fromGravitino(result));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"nchar", "nvarchar", "varchar"})
  void testCharacterDefaultMatchesColumnType(String typeName) {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean(typeName);
    type.setColumnSize(50);
    Assertions.assertEquals(
        Literals.of("O'Brien", new SqlServerTypeConverter().toGravitino(type)),
        converter.toGravitino(type, "(N'O''Brien')", false, true));
    Assertions.assertEquals(
        Literals.of("NULL", new SqlServerTypeConverter().toGravitino(type)),
        converter.toGravitino(type, "('NULL')", false, true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"varchar", "nvarchar"})
  void testMaxCharacterDefaultUsesString(String typeName) {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean(typeName);
    type.setColumnSize(Integer.MAX_VALUE);
    Assertions.assertEquals(
        Literals.stringLiteral("hello"), converter.toGravitino(type, "('hello')", false, true));
  }

  @Test
  void testGetdateMapping() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("datetime2");
    Expression result = converter.toGravitino(type, "(getdate())", false, false);
    Assertions.assertEquals(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, result);
  }

  @Test
  void testCurrentTimestampMapping() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("datetime2");
    Expression result = converter.toGravitino(type, "(CURRENT_TIMESTAMP)", false, false);
    Assertions.assertEquals(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, result);
  }

  @Test
  void testNullLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("varchar");
    type.setColumnSize(100);
    Expression result = converter.toGravitino(type, "(NULL)", false, true);
    Assertions.assertEquals(Literals.NULL, result);
  }

  @Test
  void testNullLiteralQuoted() {
    // SQL Server may store DEFAULT NULL as ('NULL') for non-string types
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("int");
    Expression result = converter.toGravitino(type, "('NULL')", false, true);
    Assertions.assertEquals(Literals.NULL, result);
  }

  @Test
  void testNullStringLiteralForVarchar() {
    // For string types, ('NULL') is a valid string literal default, not Literals.NULL
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("varchar");
    type.setColumnSize(100);
    Expression result = converter.toGravitino(type, "('NULL')", false, true);
    Assertions.assertEquals(Literals.varcharLiteral(100, "NULL"), result);
  }

  @Test
  void testIntegerLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("int");
    Expression result = converter.toGravitino(type, "((42))", false, false);
    Assertions.assertEquals(Literals.integerLiteral(42), result);
  }

  @Test
  void testBigintLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("bigint");
    Expression result = converter.toGravitino(type, "((100))", false, false);
    Assertions.assertEquals(Literals.longLiteral(100L), result);
  }

  @Test
  void testBitLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("bit");
    Expression result = converter.toGravitino(type, "((1))", false, false);
    Assertions.assertEquals(Literals.booleanLiteral(true), result);
  }

  @Test
  void testBitLiteralFalse() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("bit");
    Expression result = converter.toGravitino(type, "((0))", false, false);
    Assertions.assertEquals(Literals.booleanLiteral(false), result);
  }

  @Test
  void testTinyintLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("tinyint");
    Expression result = converter.toGravitino(type, "((1))", false, false);
    Assertions.assertEquals(Literals.byteLiteral((byte) 1), result);
  }

  @Test
  void testFromGravitinoCurrentTimestamp() {
    String result = converter.fromGravitino(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP);
    Assertions.assertEquals("GETDATE()", result);
  }

  @Test
  void testFromGravitinoNull() {
    String result = converter.fromGravitino(Literals.NULL);
    Assertions.assertEquals("NULL", result);
  }

  @Test
  void testFromGravitinoBoolean() {
    String result = converter.fromGravitino(Literals.booleanLiteral(true));
    Assertions.assertEquals("1", result);
  }

  @Test
  void testFromGravitinoBooleanFalse() {
    String result = converter.fromGravitino(Literals.booleanLiteral(false));
    Assertions.assertEquals("0", result);
  }

  @Test
  void testFromGravitinoNumeric() {
    String result = converter.fromGravitino(Literals.integerLiteral(42));
    Assertions.assertEquals("42", result);
  }

  @Test
  void testFromGravitinoString() {
    String result = converter.fromGravitino(Literals.stringLiteral("hello"));
    Assertions.assertEquals("N'hello'", result);
  }

  @Test
  void testFromGravitinoDefaultValueNotSet() {
    String result = converter.fromGravitino(DEFAULT_VALUE_NOT_SET);
    Assertions.assertNull(result);
  }

  @Test
  void testVarcharLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("varchar");
    type.setColumnSize(100);
    Expression result = converter.toGravitino(type, "('hello')", false, false);
    Assertions.assertEquals(Literals.varcharLiteral(100, "hello"), result);
  }

  @Test
  void testCharLiteral() {
    JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean("char");
    type.setColumnSize(10);
    Expression result = converter.toGravitino(type, "('abc')", false, false);
    Assertions.assertEquals(Literals.of("abc", Types.FixedCharType.of(10)), result);
  }

  @Test
  void testUnicodeDefaultWriteRoundTrip() {
    for (String name : new String[] {"nchar", "nvarchar", "varchar"}) {
      for (int length : new int[] {100, Integer.MAX_VALUE}) {
        if (name.equals("nchar") && length == Integer.MAX_VALUE) {
          continue;
        }
        JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean(name);
        type.setColumnSize(length);
        Expression literal = converter.toGravitino(type, "(N'汉字 café O''Brien')", false, true);
        String sql = converter.fromGravitino(literal);
        Assertions.assertEquals("N'汉字 café O''Brien'", sql);
        Assertions.assertEquals(literal, converter.toGravitino(type, sql, false, true));
      }
    }
  }
}
