/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleTypeConverter {

  private static final OracleTypeConverter CONVERTER = new OracleTypeConverter();

  @Test
  void testToGravitinoNumberMapping() {
    JdbcTypeConverter.JdbcTypeBean number =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    number.setColumnSize(10);
    number.setScale(0);
    Assertions.assertEquals(Types.IntegerType.get(), CONVERTER.toGravitino(number));

    JdbcTypeConverter.JdbcTypeBean decimal =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    decimal.setColumnSize(20);
    decimal.setScale(2);
    Assertions.assertEquals(Types.DecimalType.of(20, 2), CONVERTER.toGravitino(decimal));

    JdbcTypeConverter.JdbcTypeBean singleDigitNumber =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    singleDigitNumber.setColumnSize(1);
    singleDigitNumber.setScale(0);
    Assertions.assertEquals(Types.ByteType.get(), CONVERTER.toGravitino(singleDigitNumber));

    JdbcTypeConverter.JdbcTypeBean longNumber =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    longNumber.setColumnSize(19);
    longNumber.setScale(0);
    Assertions.assertEquals(Types.LongType.get(), CONVERTER.toGravitino(longNumber));

    JdbcTypeConverter.JdbcTypeBean unspecified =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    Assertions.assertEquals(Types.ExternalType.of("NUMBER"), CONVERTER.toGravitino(unspecified));

    JdbcTypeConverter.JdbcTypeBean unconstrained =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    unconstrained.setColumnSize(0);
    unconstrained.setScale(-127);
    Assertions.assertEquals(Types.DecimalType.of(38, 0), CONVERTER.toGravitino(unconstrained));

    JdbcTypeConverter.JdbcTypeBean nativeBoolean =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.BOOLEAN);
    Assertions.assertEquals(Types.BooleanType.get(), CONVERTER.toGravitino(nativeBoolean));
  }

  @Test
  void testToGravitinoCharacterAndTimestampMapping() {
    JdbcTypeConverter.JdbcTypeBean varchar2 =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.VARCHAR2);
    varchar2.setColumnSize(32);
    Assertions.assertEquals(Types.VarCharType.of(32), CONVERTER.toGravitino(varchar2));

    JdbcTypeConverter.JdbcTypeBean nchar =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NCHAR);
    nchar.setColumnSize(10);
    Assertions.assertEquals(Types.ExternalType.of("NCHAR(10)"), CONVERTER.toGravitino(nchar));

    // Oracle JDBC normally returns "TIMESTAMP(6)" in TYPE_NAME, but some drivers may return
    // just "TIMESTAMP" without parentheses; in that case the precision comes from DECIMAL_DIGITS
    // (mapped to scale on the type bean), NOT from COLUMN_SIZE which is the byte width.
    JdbcTypeConverter.JdbcTypeBean ts =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.TIMESTAMP);
    ts.setScale(6);
    Assertions.assertEquals(Types.TimestampType.withoutTimeZone(6), CONVERTER.toGravitino(ts));

    JdbcTypeConverter.JdbcTypeBean tsWithPrecision =
        new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP(3)");
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(3), CONVERTER.toGravitino(tsWithPrecision));

    JdbcTypeConverter.JdbcTypeBean tsWithTimeZone =
        new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP(4) WITH TIME ZONE");
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(4), CONVERTER.toGravitino(tsWithTimeZone));

    JdbcTypeConverter.JdbcTypeBean tsWithTimeZoneByScale =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.TIMESTAMP_WITH_TIME_ZONE);
    tsWithTimeZoneByScale.setScale(3);
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(3), CONVERTER.toGravitino(tsWithTimeZoneByScale));

    // TIMESTAMP WITH LOCAL TIME ZONE is rejected: Gravitino TimestampType cannot
    // distinguish it from TIMESTAMP WITH TIME ZONE, so a round trip would lose
    // semantics. We refuse the conversion instead of mapping it lossily.
    JdbcTypeConverter.JdbcTypeBean tsWithLocalTimeZone =
        new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP(6) WITH LOCAL TIME ZONE");
    UnsupportedOperationException localTzWithPrecisionEx =
        Assertions.assertThrows(
            UnsupportedOperationException.class, () -> CONVERTER.toGravitino(tsWithLocalTimeZone));
    Assertions.assertTrue(localTzWithPrecisionEx.getMessage().contains("WITH LOCAL TIME ZONE"));

    JdbcTypeConverter.JdbcTypeBean tsWithLocalTimeZoneNoPrecision =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.TIMESTAMP_WITH_LOCAL_TIME_ZONE);
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> CONVERTER.toGravitino(tsWithLocalTimeZoneNoPrecision));
  }

  @Test
  void testFromGravitinoMappingAndUnsupportedDate() {
    Assertions.assertEquals("NUMBER(10)", CONVERTER.fromGravitino(Types.IntegerType.get()));
    Assertions.assertEquals("NUMBER(1)", CONVERTER.fromGravitino(Types.BooleanType.get()));
    Assertions.assertEquals("CLOB", CONVERTER.fromGravitino(Types.StringType.get()));
    Assertions.assertEquals("VARCHAR2(16)", CONVERTER.fromGravitino(Types.VarCharType.of(16)));
    Assertions.assertEquals(
        "TIMESTAMP(6)", CONVERTER.fromGravitino(Types.TimestampType.withoutTimeZone()));
    Assertions.assertEquals(
        "TIMESTAMP(3)", CONVERTER.fromGravitino(Types.TimestampType.withoutTimeZone(3)));
    Assertions.assertEquals(
        "TIMESTAMP(6) WITH TIME ZONE", CONVERTER.fromGravitino(Types.TimestampType.withTimeZone()));
    Assertions.assertEquals(
        "TIMESTAMP(4) WITH TIME ZONE",
        CONVERTER.fromGravitino(Types.TimestampType.withTimeZone(4)));

    UnsupportedOperationException exception =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> CONVERTER.fromGravitino(Types.DateType.get()));
    Assertions.assertTrue(exception.getMessage().contains("DateType"));

    try {
      CONVERTER.setNativeBooleanSupported(true);
      Assertions.assertEquals("BOOLEAN", CONVERTER.fromGravitino(Types.BooleanType.get()));
    } finally {
      CONVERTER.setNativeBooleanSupported(false);
    }
  }
}
