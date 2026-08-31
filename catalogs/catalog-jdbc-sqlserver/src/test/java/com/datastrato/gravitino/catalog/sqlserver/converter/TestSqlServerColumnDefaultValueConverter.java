/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
    Assertions.assertEquals("'hello'", result);
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
}
