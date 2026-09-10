/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleTypeConverter {

  private static final OracleTypeConverter CONVERTER = new OracleTypeConverter();

  @Test
  void testToGravitinoNumberMapping() {
    // NUMBER(p,0) preserves the declared precision as Decimal(p,0) instead of bucketing to a
    // fixed Java integer width. This keeps precision recoverable and avoids reporting a type too
    // narrow for the values the source column permits.
    int[] scaleZeroPrecisions = {1, 4, 5, 9, 10, 11, 18, 19, 20, 37, 38};
    for (int p : scaleZeroPrecisions) {
      JdbcTypeConverter.JdbcTypeBean number =
          new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
      number.setColumnSize(p);
      number.setScale(0);
      Assertions.assertEquals(
          Types.DecimalType.of(p, 0),
          CONVERTER.toGravitino(number),
          "NUMBER(" + p + ",0) should map to decimal(" + p + ",0)");
    }

    // NUMBER(p) with no explicit scale is a scale-0 integer in Oracle and maps the same way.
    JdbcTypeConverter.JdbcTypeBean numberNoScale =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    numberNoScale.setColumnSize(10);
    Assertions.assertEquals(Types.DecimalType.of(10, 0), CONVERTER.toGravitino(numberNoScale));

    JdbcTypeConverter.JdbcTypeBean decimal =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    decimal.setColumnSize(20);
    decimal.setScale(2);
    Assertions.assertEquals(Types.DecimalType.of(20, 2), CONVERTER.toGravitino(decimal));

    JdbcTypeConverter.JdbcTypeBean unspecified =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    Assertions.assertEquals(Types.ExternalType.of("NUMBER"), CONVERTER.toGravitino(unspecified));

    JdbcTypeConverter.JdbcTypeBean unconstrained =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    unconstrained.setColumnSize(0);
    unconstrained.setScale(-127);
    Assertions.assertEquals(Types.DecimalType.of(38, 0), CONVERTER.toGravitino(unconstrained));

    // A non-positive precision that is NOT the unconstrained sentinel (precision 0, scale not -127)
    // must not reach Decimal.of(0, ...), which would throw. Fall back to the max Oracle precision.
    JdbcTypeConverter.JdbcTypeBean zeroPrecisionScaleZero =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    zeroPrecisionScaleZero.setColumnSize(0);
    zeroPrecisionScaleZero.setScale(0);
    Assertions.assertEquals(
        Types.DecimalType.of(38, 0), CONVERTER.toGravitino(zeroPrecisionScaleZero));

    JdbcTypeConverter.JdbcTypeBean zeroPrecisionPositiveScale =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER);
    zeroPrecisionPositiveScale.setColumnSize(0);
    zeroPrecisionPositiveScale.setScale(2);
    Assertions.assertEquals(
        Types.DecimalType.of(38, 2), CONVERTER.toGravitino(zeroPrecisionPositiveScale));

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

    // TIMESTAMP WITH LOCAL TIME ZONE cannot be distinguished from TIMESTAMP WITH TIME ZONE by
    // Gravitino TimestampType, so it is preserved as an ExternalType instead of failing the whole
    // table load. The ExternalType keeps the exact Oracle type name (with precision) and stays
    // distinguishable from the plain "WITH TIME ZONE" mapping.
    JdbcTypeConverter.JdbcTypeBean tsWithLocalTimeZone =
        new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP(6) WITH LOCAL TIME ZONE");
    Assertions.assertEquals(
        Types.ExternalType.of("TIMESTAMP(6) WITH LOCAL TIME ZONE"),
        CONVERTER.toGravitino(tsWithLocalTimeZone));

    JdbcTypeConverter.JdbcTypeBean tsWithLocalTimeZoneNoPrecision =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.TIMESTAMP_WITH_LOCAL_TIME_ZONE);
    Assertions.assertEquals(
        Types.ExternalType.of("TIMESTAMP WITH LOCAL TIME ZONE"),
        CONVERTER.toGravitino(tsWithLocalTimeZoneNoPrecision));
  }

  @Test
  void testFromGravitinoMapping() {
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

    // A Gravitino DateType maps to Oracle DATE (a date + time to the second), which is the
    // reversible path for recreating a source Oracle DATE column read as Timestamp(0).
    Assertions.assertEquals("DATE", CONVERTER.fromGravitino(Types.DateType.get()));

    try {
      CONVERTER.setNativeBooleanSupported(true);
      Assertions.assertEquals("BOOLEAN", CONVERTER.fromGravitino(Types.BooleanType.get()));
    } finally {
      CONVERTER.setNativeBooleanSupported(false);
    }
  }

  @Test
  void testDateReadsAsSecondPrecisionTimestamp() {
    // Oracle DATE is read as a second-precision Timestamp (not an ExternalType), so the engine
    // connectors (Spark/Flink/Trino) surface it as a usable temporal value rather than a string.
    JdbcTypeConverter.JdbcTypeBean date =
        new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.DATE);
    Assertions.assertEquals(Types.TimestampType.withoutTimeZone(0), CONVERTER.toGravitino(date));
  }

  @Test
  void testDateTypeWritesBackToOracleDate() {
    // Writing a Gravitino DateType produces Oracle DATE, the reversible path for recreating a
    // source Oracle DATE column. ExternalType("DATE") remains supported for callers that prefer it.
    Assertions.assertEquals("DATE", CONVERTER.fromGravitino(Types.DateType.get()));
    Assertions.assertEquals("DATE", CONVERTER.fromGravitino(Types.ExternalType.of("DATE")));
  }

  @Test
  void testLocalTimeZoneRoundTripsAsExternalType() {
    JdbcTypeConverter.JdbcTypeBean localTz =
        new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP(6) WITH LOCAL TIME ZONE");
    Type gravitinoType = CONVERTER.toGravitino(localTz);
    Assertions.assertEquals(
        Types.ExternalType.of("TIMESTAMP(6) WITH LOCAL TIME ZONE"), gravitinoType);
    Assertions.assertEquals(
        "TIMESTAMP(6) WITH LOCAL TIME ZONE", CONVERTER.fromGravitino(gravitinoType));
  }
}
