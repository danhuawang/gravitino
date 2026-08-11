/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import java.util.stream.Stream;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property-style tests for SqlServerTypeConverter. Validates round-trip type conversion and
 * nvarchar/StringType mapping.
 */
public class TestSqlServerTypeConverterProperty {

  private final SqlServerTypeConverter converter = new SqlServerTypeConverter();

  /** Provides Gravitino types that should round-trip through SQL Server conversion. */
  static Stream<Arguments> roundTripTypes() {
    return Stream.of(
        Arguments.of(Types.ByteType.get(), "tinyint"),
        Arguments.of(Types.ShortType.get(), "smallint"),
        Arguments.of(Types.IntegerType.get(), "int"),
        Arguments.of(Types.LongType.get(), "bigint"),
        Arguments.of(Types.BooleanType.get(), "bit"),
        Arguments.of(Types.FloatType.get(), "real"),
        Arguments.of(Types.DoubleType.get(), "float"),
        Arguments.of(Types.DateType.get(), "date"),
        Arguments.of(Types.StringType.get(), "nvarchar(max)"),
        Arguments.of(Types.BinaryType.get(), "varbinary(max)"),
        Arguments.of(Types.UUIDType.get(), "uniqueidentifier"),
        Arguments.of(Types.DecimalType.of(10, 2), "decimal(10,2)"),
        Arguments.of(Types.DecimalType.of(38, 0), "decimal(38,0)"),
        Arguments.of(Types.DecimalType.of(1, 0), "decimal(1,0)"),
        Arguments.of(Types.TimeType.of(3), "time(3)"),
        Arguments.of(Types.TimeType.of(0), "time(0)"),
        Arguments.of(Types.TimeType.of(7), "time(7)"),
        Arguments.of(Types.TimestampType.withoutTimeZone(6), "datetime2(6)"),
        Arguments.of(Types.TimestampType.withoutTimeZone(0), "datetime2(0)"),
        Arguments.of(Types.FixedCharType.of(1), "char(1)"),
        Arguments.of(Types.FixedCharType.of(100), "char(100)"),
        Arguments.of(Types.VarCharType.of(1), "varchar(1)"),
        Arguments.of(Types.VarCharType.of(8000), "varchar(8000)"),
        Arguments.of(Types.FixedType.of(1), "binary(1)"),
        Arguments.of(Types.FixedType.of(100), "binary(100)"));
  }

  @ParameterizedTest
  @MethodSource("roundTripTypes")
  void testFromGravitinoProducesExpectedSqlServerType(Type gravitinoType, String expectedSql) {
    String sqlType = converter.fromGravitino(gravitinoType);
    Assertions.assertEquals(
        expectedSql,
        sqlType,
        String.format(
            "fromGravitino(%s) should produce '%s' but got '%s'",
            gravitinoType.simpleString(), expectedSql, sqlType));
  }

  /** Provides SQL Server type beans that should round-trip back to the same Gravitino type. */
  static Stream<Arguments> sqlServerToGravitinoRoundTrip() {
    return Stream.of(
        Arguments.of("tinyint", null, null, null, Types.ByteType.get()),
        Arguments.of("smallint", null, null, null, Types.ShortType.get()),
        Arguments.of("int", null, null, null, Types.IntegerType.get()),
        Arguments.of("bigint", null, null, null, Types.LongType.get()),
        Arguments.of("bit", null, null, null, Types.BooleanType.get()),
        Arguments.of("real", null, null, null, Types.FloatType.get()),
        Arguments.of("float", null, null, null, Types.DoubleType.get()),
        Arguments.of("date", null, null, null, Types.DateType.get()),
        Arguments.of("decimal", 10, 2, null, Types.DecimalType.of(10, 2)),
        Arguments.of("time", null, null, 3, Types.TimeType.of(3)),
        Arguments.of("datetime2", null, null, 6, Types.TimestampType.withoutTimeZone(6)),
        Arguments.of("char", 10, null, null, Types.FixedCharType.of(10)),
        Arguments.of("varchar", 255, null, null, Types.VarCharType.of(255)),
        Arguments.of("nvarchar", 50, null, null, Types.VarCharType.of(50)),
        Arguments.of("nvarchar", Integer.MAX_VALUE, null, null, Types.StringType.get()),
        Arguments.of("binary", 50, null, null, Types.FixedType.of(50)),
        Arguments.of("varbinary", null, null, null, Types.BinaryType.get()),
        Arguments.of("uniqueidentifier", null, null, null, Types.UUIDType.get()));
  }

  @ParameterizedTest
  @MethodSource("sqlServerToGravitinoRoundTrip")
  void testToGravitinoProducesExpectedType(
      String typeName,
      Integer columnSize,
      Integer scale,
      Integer datetimePrecision,
      Type expectedType) {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean(typeName);
    if (columnSize != null) {
      bean.setColumnSize(columnSize);
    }
    if (scale != null) {
      bean.setScale(scale);
    }
    if (datetimePrecision != null) {
      bean.setDatetimePrecision(datetimePrecision);
    }
    Type result = converter.toGravitino(bean);
    Assertions.assertEquals(
        expectedType,
        result,
        String.format(
            "toGravitino(%s) should produce %s but got %s", typeName, expectedType, result));
  }

  /** Provides bounded, max-length, and unknown-length nvarchar variants. */
  static Stream<Arguments> nvarcharVariants() {
    return Stream.of(
        Arguments.of("nvarchar", 100, Types.VarCharType.of(100)),
        Arguments.of("nvarchar", 4000, Types.VarCharType.of(4000)),
        Arguments.of("nvarchar", Integer.MAX_VALUE, Types.StringType.get()),
        Arguments.of("nvarchar", null, Types.StringType.get()));
  }

  @ParameterizedTest
  @MethodSource("nvarcharVariants")
  void testNvarcharPreservesLength(String typeName, Integer columnSize, Type expectedType) {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean(typeName);
    if (columnSize != null) {
      bean.setColumnSize(columnSize);
    }
    Type result = converter.toGravitino(bean);
    Assertions.assertEquals(expectedType, result);
  }

  /** Verify nchar maps to ExternalType. */
  static Stream<Arguments> ncharVariants() {
    return Stream.of(Arguments.of(10), Arguments.of(50), Arguments.of(100));
  }

  @ParameterizedTest
  @MethodSource("ncharVariants")
  void testNcharMapsToExternalType(int length) {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("nchar");
    bean.setColumnSize(length);
    Type result = converter.toGravitino(bean);
    Assertions.assertInstanceOf(Types.ExternalType.class, result);
    Assertions.assertEquals("nchar(" + length + ")", ((Types.ExternalType) result).catalogString());
  }

  /** Verify StringType always maps back to nvarchar. */
  @Test
  void testStringTypeAlwaysMapsToNvarchar() {
    String result = converter.fromGravitino(Types.StringType.get());
    Assertions.assertEquals("nvarchar(max)", result);
  }

  /** Verify ExternalType pass-through. */
  static Stream<Arguments> externalTypes() {
    return Stream.of(
        Arguments.of("geography"),
        Arguments.of("geometry"),
        Arguments.of("xml"),
        Arguments.of("sql_variant"),
        Arguments.of("money"),
        Arguments.of("smallmoney"),
        Arguments.of("datetime"),
        Arguments.of("smalldatetime"),
        Arguments.of("text"),
        Arguments.of("ntext"),
        Arguments.of("image"),
        Arguments.of("rowversion"));
  }

  @ParameterizedTest
  @MethodSource("externalTypes")
  void testExternalTypePassThrough(String typeName) {
    Types.ExternalType ext = Types.ExternalType.of(typeName);
    String result = converter.fromGravitino(ext);
    Assertions.assertEquals(typeName, result, "ExternalType should pass through as-is");
  }
}
