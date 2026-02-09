/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.maxcompute.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link MaxComputeTypeConverter}. */
public class TestMaxComputeTypeConverter {

  private MaxComputeTypeConverter typeConverter;

  @BeforeEach
  void setUp() {
    typeConverter = new MaxComputeTypeConverter();
  }

  // ==================== toGravitino Tests ====================

  @Test
  void testToGravitinoBoolean() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("BOOLEAN");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.BooleanType.get(), result);
  }

  @Test
  void testToGravitinoTinyint() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("TINYINT");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.ByteType.get(), result);
  }

  @Test
  void testToGravitinoSmallint() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("SMALLINT");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.ShortType.get(), result);
  }

  @Test
  void testToGravitinoInt() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("INT");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.IntegerType.get(), result);
  }

  @Test
  void testToGravitinoBigint() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("BIGINT");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.LongType.get(), result);
  }

  @Test
  void testToGravitinoFloat() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("FLOAT");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.FloatType.get(), result);
  }

  @Test
  void testToGravitinoDouble() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("DOUBLE");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.DoubleType.get(), result);
  }

  @Test
  void testToGravitinoDecimal() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("DECIMAL");
    typeBean.setColumnSize(18);
    typeBean.setScale(6);
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.DecimalType.of(18, 6), result);
  }

  @Test
  void testToGravitinoDecimalWithMaxPrecision() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("DECIMAL");
    typeBean.setColumnSize(50); // Exceeds max, should be capped to 38
    typeBean.setScale(20); // Within extended scale range, should be preserved
    Type result = typeConverter.toGravitino(typeBean);
    // Precision capped to 38, scale preserved as 20
    assertEquals(Types.DecimalType.of(38, 20), result);
  }

  @Test
  void testToGravitinoString() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("STRING");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.StringType.get(), result);
  }

  @Test
  void testToGravitinoVarchar() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("VARCHAR");
    typeBean.setColumnSize(100);
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.VarCharType.of(100), result);
  }

  @Test
  void testToGravitinoChar() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("CHAR");
    typeBean.setColumnSize(50);
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.FixedCharType.of(50), result);
  }

  @Test
  void testToGravitinoBinary() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("BINARY");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.BinaryType.get(), result);
  }

  @Test
  void testToGravitinoDate() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("DATE");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.DateType.get(), result);
  }

  @Test
  void testToGravitinoDatetime() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("DATETIME");
    Type result = typeConverter.toGravitino(typeBean);
    // DATETIME in MaxCompute has millisecond precision (3)
    assertEquals(Types.TimestampType.withoutTimeZone(3), result);
  }

  @Test
  void testToGravitinoTimestamp() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP");
    Type result = typeConverter.toGravitino(typeBean);
    // TIMESTAMP in MaxCompute has nanosecond precision (9)
    assertEquals(Types.TimestampType.withoutTimeZone(9), result);
  }

  @Test
  void testToGravitinoTimestampNtz() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("TIMESTAMP_NTZ");
    Type result = typeConverter.toGravitino(typeBean);
    // TIMESTAMP_NTZ in MaxCompute has nanosecond precision (9)
    assertEquals(Types.TimestampType.withoutTimeZone(9), result);
  }

  @Test
  void testToGravitinoComplexType() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("ARRAY<STRING>");
    Type result = typeConverter.toGravitino(typeBean);
    assertInstanceOf(Types.ExternalType.class, result);
    assertEquals("ARRAY<STRING>", ((Types.ExternalType) result).catalogString());
  }

  @Test
  void testToGravitinoMapType() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("MAP<STRING,INT>");
    Type result = typeConverter.toGravitino(typeBean);
    assertInstanceOf(Types.ExternalType.class, result);
  }

  @Test
  void testToGravitinoStructType() {
    JdbcTypeConverter.JdbcTypeBean typeBean =
        new JdbcTypeConverter.JdbcTypeBean("STRUCT<name:STRING,age:INT>");
    Type result = typeConverter.toGravitino(typeBean);
    assertInstanceOf(Types.ExternalType.class, result);
  }

  // ==================== fromGravitino Tests ====================

  @Test
  void testFromGravitinoBoolean() {
    String result = typeConverter.fromGravitino(Types.BooleanType.get());
    assertEquals("boolean", result);
  }

  @Test
  void testFromGravitinoByte() {
    String result = typeConverter.fromGravitino(Types.ByteType.get());
    assertEquals("tinyint", result);
  }

  @Test
  void testFromGravitinoShort() {
    String result = typeConverter.fromGravitino(Types.ShortType.get());
    assertEquals("smallint", result);
  }

  @Test
  void testFromGravitinoInteger() {
    String result = typeConverter.fromGravitino(Types.IntegerType.get());
    assertEquals("int", result);
  }

  @Test
  void testFromGravitinoLong() {
    String result = typeConverter.fromGravitino(Types.LongType.get());
    assertEquals("bigint", result);
  }

  @Test
  void testFromGravitinoFloat() {
    String result = typeConverter.fromGravitino(Types.FloatType.get());
    assertEquals("float", result);
  }

  @Test
  void testFromGravitinoDouble() {
    String result = typeConverter.fromGravitino(Types.DoubleType.get());
    assertEquals("double", result);
  }

  @Test
  void testFromGravitinoDecimal() {
    String result = typeConverter.fromGravitino(Types.DecimalType.of(18, 6));
    assertEquals("decimal(18,6)", result);
  }

  @Test
  void testFromGravitinoString() {
    String result = typeConverter.fromGravitino(Types.StringType.get());
    assertEquals("string", result);
  }

  @Test
  void testFromGravitinoVarchar() {
    String result = typeConverter.fromGravitino(Types.VarCharType.of(100));
    assertEquals("varchar(100)", result);
  }

  @Test
  void testFromGravitinoChar() {
    String result = typeConverter.fromGravitino(Types.FixedCharType.of(50));
    assertEquals("char(50)", result);
  }

  @Test
  void testFromGravitinoBinary() {
    String result = typeConverter.fromGravitino(Types.BinaryType.get());
    assertEquals("binary", result);
  }

  @Test
  void testFromGravitinoDate() {
    String result = typeConverter.fromGravitino(Types.DateType.get());
    assertEquals("date", result);
  }

  @Test
  void testFromGravitinoTimestamp() {
    String result = typeConverter.fromGravitino(Types.TimestampType.withoutTimeZone());
    assertEquals("timestamp", result);
  }

  @Test
  void testFromGravitinoExternalType() {
    String result = typeConverter.fromGravitino(Types.ExternalType.of("ARRAY<STRING>"));
    assertEquals("ARRAY<STRING>", result);
  }

  @Test
  void testFromGravitinoUnparsedType() {
    String result = typeConverter.fromGravitino(Types.UnparsedType.of("MAP<STRING,INT>"));
    assertEquals("MAP<STRING,INT>", result);
  }

  // ==================== Validation Tests ====================

  @Test
  void testFromGravitinoDecimalInvalidPrecision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> typeConverter.fromGravitino(Types.DecimalType.of(50, 10)));
  }

  @Test
  void testFromGravitinoDecimalInvalidScale() {
    assertThrows(
        IllegalArgumentException.class,
        () -> typeConverter.fromGravitino(Types.DecimalType.of(20, 25)));
  }

  @Test
  void testFromGravitinoVarcharInvalidLength() {
    assertThrows(
        IllegalArgumentException.class,
        () -> typeConverter.fromGravitino(Types.VarCharType.of(100000)));
  }

  @Test
  void testFromGravitinoCharInvalidLength() {
    assertThrows(
        IllegalArgumentException.class,
        () -> typeConverter.fromGravitino(Types.FixedCharType.of(300)));
  }

  @Test
  void testFromGravitinoTimeNotSupported() {
    assertThrows(
        IllegalArgumentException.class, () -> typeConverter.fromGravitino(Types.TimeType.of(6)));
  }

  @Test
  void testFromGravitinoUUIDNotSupported() {
    assertThrows(
        IllegalArgumentException.class, () -> typeConverter.fromGravitino(Types.UUIDType.get()));
  }

  @Test
  void testFromGravitinoIntervalYear() {
    String result = typeConverter.fromGravitino(Types.IntervalYearType.get());
    assertEquals("interval_year_month", result);
  }

  @Test
  void testFromGravitinoIntervalDay() {
    String result = typeConverter.fromGravitino(Types.IntervalDayType.get());
    assertEquals("interval_day_time", result);
  }

  @Test
  void testFromGravitinoNullType() {
    String result = typeConverter.fromGravitino(Types.NullType.get());
    assertEquals("void", result);
  }

  @Test
  void testToGravitinoVoid() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("VOID");
    Type result = typeConverter.toGravitino(typeBean);
    assertEquals(Types.NullType.get(), result);
  }

  @Test
  void testToGravitinoJson() {
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("JSON");
    Type result = typeConverter.toGravitino(typeBean);
    assertInstanceOf(Types.ExternalType.class, result);
    assertEquals("JSON", ((Types.ExternalType) result).catalogString());
  }

  @Test
  void testToGravitinoIntervalYearMonth() {
    JdbcTypeConverter.JdbcTypeBean typeBean =
        new JdbcTypeConverter.JdbcTypeBean("INTERVAL_YEAR_MONTH");
    Type result = typeConverter.toGravitino(typeBean);
    assertInstanceOf(Types.ExternalType.class, result);
  }

  @Test
  void testToGravitinoIntervalDayTime() {
    JdbcTypeConverter.JdbcTypeBean typeBean =
        new JdbcTypeConverter.JdbcTypeBean("INTERVAL_DAY_TIME");
    Type result = typeConverter.toGravitino(typeBean);
    assertInstanceOf(Types.ExternalType.class, result);
  }

  @Test
  void testToGravitinoVarcharInvalidLength() {
    // Test VARCHAR with invalid length (0 or negative)
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("VARCHAR");
    typeBean.setColumnSize(0);
    Type result = typeConverter.toGravitino(typeBean);
    // Should fall back to StringType
    assertEquals(Types.StringType.get(), result);
  }

  @Test
  void testToGravitinoVarcharExceedsMax() {
    // Test VARCHAR with length exceeding max
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("VARCHAR");
    typeBean.setColumnSize(100000);
    Type result = typeConverter.toGravitino(typeBean);
    // Should fall back to StringType
    assertEquals(Types.StringType.get(), result);
  }

  @Test
  void testToGravitinoDecimalWithZeroPrecision() {
    // Test DECIMAL with zero precision
    JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean("DECIMAL");
    typeBean.setColumnSize(0);
    typeBean.setScale(0);
    Type result = typeConverter.toGravitino(typeBean);
    // Should use max precision
    assertEquals(Types.DecimalType.of(38, 0), result);
  }

  @Test
  void testFromGravitinoTimestampWithTimezone() {
    // Timestamp with timezone should still map to TIMESTAMP
    String result = typeConverter.fromGravitino(Types.TimestampType.withTimeZone());
    assertEquals("timestamp", result);
  }

  // ==================== Case Insensitivity Tests ====================

  @Test
  void testToGravitinoCaseInsensitive() {
    // Test uppercase
    JdbcTypeConverter.JdbcTypeBean upperBean = new JdbcTypeConverter.JdbcTypeBean("BIGINT");
    assertEquals(Types.LongType.get(), typeConverter.toGravitino(upperBean));

    // Test lowercase
    JdbcTypeConverter.JdbcTypeBean lowerBean = new JdbcTypeConverter.JdbcTypeBean("bigint");
    assertEquals(Types.LongType.get(), typeConverter.toGravitino(lowerBean));

    // Test mixed case
    JdbcTypeConverter.JdbcTypeBean mixedBean = new JdbcTypeConverter.JdbcTypeBean("BigInt");
    assertEquals(Types.LongType.get(), typeConverter.toGravitino(mixedBean));
  }
}
