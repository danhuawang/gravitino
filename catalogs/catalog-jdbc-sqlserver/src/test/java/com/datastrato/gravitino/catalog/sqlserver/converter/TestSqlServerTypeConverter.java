/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Unit tests for SqlServerTypeConverter. */
public class TestSqlServerTypeConverter {

  private final SqlServerTypeConverter converter = new SqlServerTypeConverter();

  // --- toGravitino tests ---

  @Test
  void testTinyintToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("tinyint");
    Assertions.assertEquals(Types.ByteType.get(), converter.toGravitino(bean));
  }

  @Test
  void testSmallintToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("smallint");
    Assertions.assertEquals(Types.ShortType.get(), converter.toGravitino(bean));
  }

  @Test
  void testIntToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("int");
    Assertions.assertEquals(Types.IntegerType.get(), converter.toGravitino(bean));
  }

  @Test
  void testBigintToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("bigint");
    Assertions.assertEquals(Types.LongType.get(), converter.toGravitino(bean));
  }

  @Test
  void testBitToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("bit");
    Assertions.assertEquals(Types.BooleanType.get(), converter.toGravitino(bean));
  }

  @Test
  void testDecimalToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("decimal");
    bean.setColumnSize(10);
    bean.setScale(2);
    Assertions.assertEquals(Types.DecimalType.of(10, 2), converter.toGravitino(bean));
  }

  @Test
  void testRealToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("real");
    Assertions.assertEquals(Types.FloatType.get(), converter.toGravitino(bean));
  }

  @Test
  void testFloatToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("float");
    Assertions.assertEquals(Types.DoubleType.get(), converter.toGravitino(bean));
  }

  @Test
  void testDateToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("date");
    Assertions.assertEquals(Types.DateType.get(), converter.toGravitino(bean));
  }

  @Test
  void testTimeToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("time");
    bean.setDatetimePrecision(3);
    Assertions.assertEquals(Types.TimeType.of(3), converter.toGravitino(bean));
  }

  @Test
  void testDatetime2ToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("datetime2");
    bean.setDatetimePrecision(6);
    Assertions.assertEquals(Types.TimestampType.withoutTimeZone(6), converter.toGravitino(bean));
  }

  @Test
  void testCharToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("char");
    bean.setColumnSize(10);
    Assertions.assertEquals(Types.FixedCharType.of(10), converter.toGravitino(bean));
  }

  @Test
  void testVarcharToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("varchar");
    bean.setColumnSize(255);
    Assertions.assertEquals(Types.VarCharType.of(255), converter.toGravitino(bean));
  }

  @Test
  void testVarcharMaxToString() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("varchar");
    bean.setColumnSize(Integer.MAX_VALUE);
    Type result = converter.toGravitino(bean);
    Assertions.assertEquals(Types.StringType.get(), result);
  }

  @Test
  void testNvarcharToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("nvarchar");
    bean.setColumnSize(50);
    Assertions.assertEquals(Types.VarCharType.of(50), converter.toGravitino(bean));
  }

  @Test
  void testNvarcharMaxToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("nvarchar");
    bean.setColumnSize(Integer.MAX_VALUE);
    Assertions.assertEquals(Types.StringType.get(), converter.toGravitino(bean));
  }

  @ParameterizedTest
  @CsvSource({
    "char,10,char(10)",
    "varchar,100,varchar(100)",
    "varchar,2147483647,nvarchar(max)",
    "nchar,10,char(10)",
    "nvarchar,100,varchar(100)",
    "nvarchar,2147483647,nvarchar(max)",
    "NVARCHAR,4000,varchar(4000)",
    "NCHAR,4000,char(4000)"
  })
  void testCharacterFamilyUsesNativeTypes(String name, int length, String expectedSql) {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean(name);
    bean.setColumnSize(length);
    Assertions.assertEquals(expectedSql, converter.fromGravitino(converter.toGravitino(bean)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"char", "nchar", "varchar", "nvarchar"})
  void testMissingCharacterLengthDoesNotInventBoundedLength(String name) {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean(name);
    Assertions.assertEquals(Types.StringType.get(), converter.toGravitino(bean));
  }

  @Test
  void testBinaryToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("binary");
    bean.setColumnSize(50);
    Assertions.assertEquals(Types.FixedType.of(50), converter.toGravitino(bean));
  }

  @Test
  void testVarbinaryToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("varbinary");
    Assertions.assertEquals(Types.BinaryType.get(), converter.toGravitino(bean));
  }

  @Test
  void testUniqueidentifierToGravitino() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("uniqueidentifier");
    Assertions.assertEquals(Types.UUIDType.get(), converter.toGravitino(bean));
  }

  // --- ExternalType mapping tests ---

  @ParameterizedTest
  @CsvSource({"10,2", "38,0", "18,0", "5,2", "18,4", "38,38"})
  void testNumericMatchesDecimal(int precision, int scale) {
    for (String typeName : new String[] {"numeric", "NUMERIC", "decimal"}) {
      JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean(typeName);
      bean.setColumnSize(precision);
      bean.setScale(scale);
      Type result = converter.toGravitino(bean);
      Assertions.assertEquals(Types.DecimalType.of(precision, scale), result);
      Assertions.assertEquals(
          "decimal(" + precision + "," + scale + ")", converter.fromGravitino(result));
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void testDatetimeoffsetUsesFractionalSecondsPrecision(Integer precision) {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("datetimeoffset");
    // Display width and scale must not become a two-argument SQL type declaration.
    bean.setColumnSize(34);
    bean.setScale(7);
    bean.setDatetimePrecision(precision);
    String expected = precision == null ? "datetimeoffset" : "datetimeoffset(" + precision + ")";
    Type result = converter.toGravitino(bean);
    Assertions.assertEquals(Types.ExternalType.of(expected), result);
    Assertions.assertEquals(expected, converter.fromGravitino(result));
  }

  @Test
  void testMoneyToExternalType() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("money");
    Type result = converter.toGravitino(bean);
    Assertions.assertInstanceOf(Types.ExternalType.class, result);
    Assertions.assertEquals("money", ((Types.ExternalType) result).catalogString());
  }

  @Test
  void testDatetimeToTimestampType() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("datetime");
    Assertions.assertEquals(Types.TimestampType.withoutTimeZone(3), converter.toGravitino(bean));
  }

  @Test
  void testNcharToFixedChar() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("nchar");
    bean.setColumnSize(10);
    Type result = converter.toGravitino(bean);
    Assertions.assertEquals(Types.FixedCharType.of(10), result);
  }

  @Test
  void testTextToExternalType() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("text");
    Type result = converter.toGravitino(bean);
    Assertions.assertInstanceOf(Types.ExternalType.class, result);
    Assertions.assertEquals("text", ((Types.ExternalType) result).catalogString());
  }

  @Test
  void testImageToExternalType() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("image");
    Type result = converter.toGravitino(bean);
    Assertions.assertInstanceOf(Types.ExternalType.class, result);
    Assertions.assertEquals("image", ((Types.ExternalType) result).catalogString());
  }

  @Test
  void testXmlToExternalType() {
    JdbcTypeConverter.JdbcTypeBean bean = new JdbcTypeConverter.JdbcTypeBean("xml");
    Type result = converter.toGravitino(bean);
    Assertions.assertInstanceOf(Types.ExternalType.class, result);
    Assertions.assertEquals("xml", ((Types.ExternalType) result).catalogString());
  }

  // --- fromGravitino tests ---

  @Test
  void testByteTypeToSqlServer() {
    Assertions.assertEquals("tinyint", converter.fromGravitino(Types.ByteType.get()));
  }

  @Test
  void testShortTypeToSqlServer() {
    Assertions.assertEquals("smallint", converter.fromGravitino(Types.ShortType.get()));
  }

  @Test
  void testIntegerTypeToSqlServer() {
    Assertions.assertEquals("int", converter.fromGravitino(Types.IntegerType.get()));
  }

  @Test
  void testLongTypeToSqlServer() {
    Assertions.assertEquals("bigint", converter.fromGravitino(Types.LongType.get()));
  }

  @Test
  void testBooleanTypeToSqlServer() {
    Assertions.assertEquals("bit", converter.fromGravitino(Types.BooleanType.get()));
  }

  @Test
  void testDecimalTypeToSqlServer() {
    Assertions.assertEquals("decimal(10,2)", converter.fromGravitino(Types.DecimalType.of(10, 2)));
  }

  @Test
  void testFloatTypeToSqlServer() {
    Assertions.assertEquals("real", converter.fromGravitino(Types.FloatType.get()));
  }

  @Test
  void testDoubleTypeToSqlServer() {
    Assertions.assertEquals("float", converter.fromGravitino(Types.DoubleType.get()));
  }

  @Test
  void testDateTypeToSqlServer() {
    Assertions.assertEquals("date", converter.fromGravitino(Types.DateType.get()));
  }

  @Test
  void testTimeTypeToSqlServer() {
    Assertions.assertEquals("time(3)", converter.fromGravitino(Types.TimeType.of(3)));
  }

  @Test
  void testTimestampTypeToSqlServer() {
    Assertions.assertEquals(
        "datetime2(6)", converter.fromGravitino(Types.TimestampType.withoutTimeZone(6)));
  }

  @Test
  void testFixedCharTypeToSqlServer() {
    Assertions.assertEquals("char(10)", converter.fromGravitino(Types.FixedCharType.of(10)));
  }

  @Test
  void testVarCharTypeToSqlServer() {
    Assertions.assertEquals("varchar(255)", converter.fromGravitino(Types.VarCharType.of(255)));
  }

  @Test
  void testStringTypeToNvarchar() {
    Assertions.assertEquals("nvarchar(max)", converter.fromGravitino(Types.StringType.get()));
  }

  @Test
  void testFixedTypeToSqlServer() {
    Assertions.assertEquals("binary(50)", converter.fromGravitino(Types.FixedType.of(50)));
  }

  @Test
  void testBinaryTypeToSqlServer() {
    Assertions.assertEquals("varbinary(max)", converter.fromGravitino(Types.BinaryType.get()));
  }

  @Test
  void testUUIDTypeToSqlServer() {
    Assertions.assertEquals("uniqueidentifier", converter.fromGravitino(Types.UUIDType.get()));
  }

  @Test
  void testExternalTypePassThrough() {
    Types.ExternalType ext = Types.ExternalType.of("geography");
    Assertions.assertEquals("geography", converter.fromGravitino(ext));
  }

  @Test
  void testTimestampWithTimeZoneRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> converter.fromGravitino(Types.TimestampType.withTimeZone()));
  }

  @Test
  void testUnrecognizedGravitinoTypeRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> converter.fromGravitino(Types.ListType.of(Types.IntegerType.get(), false)));
  }

  @Test
  void testUnicodeWarningsAreEmittedOncePerTypePerConverter() {
    LoggerContext context =
        (LoggerContext) LogManager.getContext(SqlServerTypeConverter.class.getClassLoader(), false);
    var logger = context.getLogger(SqlServerTypeConverter.class.getName());
    Appender appender = Mockito.mock(Appender.class);
    Mockito.when(appender.getName()).thenReturn("unicode-mapping-test");
    Mockito.when(appender.isStarted()).thenReturn(true);
    var originalLevel = logger.getLevel();
    logger.addAppender(appender);
    logger.setLevel(Level.WARN);
    try {
      SqlServerTypeConverter converter = new SqlServerTypeConverter();
      for (int i = 0; i < 10; i++) {
        for (String name : new String[] {"nchar", "nvarchar", "char", "varchar"}) {
          JdbcTypeConverter.JdbcTypeBean type = new JdbcTypeConverter.JdbcTypeBean(name);
          type.setColumnSize(10);
          converter.toGravitino(type);
        }
      }
      ArgumentCaptor<LogEvent> events = ArgumentCaptor.forClass(LogEvent.class);
      Mockito.verify(appender, Mockito.times(2)).append(events.capture());
      Assertions.assertTrue(
          events.getAllValues().stream()
              .allMatch(
                  event ->
                      event
                          .getMessage()
                          .getFormattedMessage()
                          .contains("source Unicode encoding")));
    } finally {
      logger.removeAppender(appender);
      logger.setLevel(originalLevel);
    }
  }
}
