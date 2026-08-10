/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import java.time.OffsetDateTime;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleColumnDefaultValueConverter {

  private final OracleColumnDefaultValueConverter converter =
      new OracleColumnDefaultValueConverter();

  @Test
  void testToGravitinoWithNullAndFunctions() {
    JdbcTypeConverter.JdbcTypeBean number = new JdbcTypeConverter.JdbcTypeBean("NUMBER");
    number.setScale(0);

    Expression noDefault = converter.toGravitino(number, null, false, false);
    Assertions.assertEquals(DEFAULT_VALUE_NOT_SET, noDefault);

    Expression nullableNull = converter.toGravitino(number, "NULL", false, true);
    Assertions.assertEquals(Literals.NULL, nullableNull);

    Expression sysdate = converter.toGravitino(number, "SYSDATE", true, false);
    Assertions.assertEquals(FunctionExpression.of("SYSDATE"), sysdate);

    Expression systimestamp = converter.toGravitino(number, "SYSTIMESTAMP", true, false);
    Assertions.assertEquals(FunctionExpression.of("SYSTIMESTAMP"), systimestamp);

    Expression sysGuid = converter.toGravitino(number, "sys_guid()", true, false);
    Assertions.assertEquals(FunctionExpression.of("SYS_GUID"), sysGuid);
  }

  @Test
  void testToGravitinoLiteralsAndUnparsed() {
    JdbcTypeConverter.JdbcTypeBean number = new JdbcTypeConverter.JdbcTypeBean("NUMBER");
    number.setScale(0);
    Assertions.assertEquals(
        Literals.longLiteral(123L), converter.toGravitino(number, "123", false, false));

    JdbcTypeConverter.JdbcTypeBean decimalType = new JdbcTypeConverter.JdbcTypeBean("NUMBER");
    decimalType.setScale(2);
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("3.14", 3, 2)),
        converter.toGravitino(decimalType, "3.14", false, false));

    JdbcTypeConverter.JdbcTypeBean varchar = new JdbcTypeConverter.JdbcTypeBean("VARCHAR2");
    Assertions.assertEquals(
        Literals.stringLiteral("ab'c"), converter.toGravitino(varchar, "'ab''c'", false, false));

    Expression expr = converter.toGravitino(varchar, "CUSTOM_EXPR()", true, false);
    Assertions.assertEquals(UnparsedExpression.of("CUSTOM_EXPR()"), expr);
  }

  @Test
  void testFromGravitino() {
    Assertions.assertNull(converter.fromGravitino(DEFAULT_VALUE_NOT_SET));
    Assertions.assertEquals("NULL", converter.fromGravitino(Literals.NULL));
    Assertions.assertEquals("SYSDATE", converter.fromGravitino(FunctionExpression.of("SYSDATE")));
    Assertions.assertEquals(
        "SYS_GUID()", converter.fromGravitino(FunctionExpression.of("SYS_GUID")));
    Assertions.assertEquals("'ab''c'", converter.fromGravitino(Literals.stringLiteral("ab'c")));
    Assertions.assertEquals(
        "DATE '2026-07-15'", converter.fromGravitino(Literals.dateLiteral("2026-07-15")));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 12:34:56'",
        converter.fromGravitino(Literals.timestampLiteral("2026-07-15T12:34:56")));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 12:34:00'",
        converter.fromGravitino(Literals.timestampLiteral("2026-07-15T12:34:00")));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 12:34:56'",
        converter.fromGravitino(
            Literals.of("2026-07-15T12:34:56", Types.TimestampType.withoutTimeZone())));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 12:34:00'",
        converter.fromGravitino(
            Literals.of("2026-07-15T12:34", Types.TimestampType.withoutTimeZone())));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 12:34:56.123456789'",
        converter.fromGravitino(
            Literals.of("2026-07-15T12:34:56.123456789", Types.TimestampType.withoutTimeZone(9))));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 12:34:56.123456789 +08:00'",
        converter.fromGravitino(
            Literals.of(
                OffsetDateTime.parse("2026-07-15T12:34:56.123456789+08:00"),
                Types.TimestampType.withTimeZone(9))));
    Assertions.assertEquals(
        "TIMESTAMP '2026-07-15 04:34:56 +00:00'",
        converter.fromGravitino(
            Literals.of("2026-07-15T04:34:56Z", Types.TimestampType.withTimeZone(9))));
    Assertions.assertEquals("1", converter.fromGravitino(Literals.booleanLiteral(true)));
    Assertions.assertEquals("0", converter.fromGravitino(Literals.booleanLiteral(false)));
    Assertions.assertEquals(
        "1", converter.fromGravitino(Literals.of("true", Types.BooleanType.get())));
    Assertions.assertEquals(
        "2", converter.fromGravitino(Literals.of("2", Types.BooleanType.get())));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> converter.fromGravitino(Literals.of("invalid", Types.BooleanType.get())));
    Assertions.assertEquals("123", converter.fromGravitino(Literals.integerLiteral(123)));
    Assertions.assertEquals(
        "RAW_SQL()", converter.fromGravitino(UnparsedExpression.of("RAW_SQL()")));
  }

  @Test
  void testNativeBooleanConversion() {
    OracleColumnDefaultValueConverter nativeBooleanConverter =
        new OracleColumnDefaultValueConverter();
    nativeBooleanConverter.setNativeBooleanSupported(true);
    Assertions.assertEquals(
        "TRUE", nativeBooleanConverter.fromGravitino(Literals.booleanLiteral(true)));
    Assertions.assertEquals(
        "FALSE", nativeBooleanConverter.fromGravitino(Literals.booleanLiteral(false)));

    JdbcTypeConverter.JdbcTypeBean type =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.BOOLEAN);
    Assertions.assertEquals(
        Literals.booleanLiteral(true),
        nativeBooleanConverter.toGravitino(type, "TRUE", false, true));
    Assertions.assertEquals(
        Literals.booleanLiteral(false),
        nativeBooleanConverter.toGravitino(type, "FALSE", false, true));
  }
}
