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
package org.apache.gravitino.trino.connector.catalog.jdbc.oracle;

import io.trino.spi.TrinoException;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleDataTypeTransformer {

  private final GeneralDataTypeTransformer transformer = new OracleDataTypeTransformer();

  // ---- getTrinoType: Oracle-specific overrides ----
  //
  // Trino's native Oracle connector represents a scale-0 NUMBER as DECIMAL regardless of
  // precision, matching the fixed-precision NUMBER DDL OracleTypeConverter emits for these
  // Gravitino types (NUMBER(1) for BooleanType, NUMBER(3) for ByteType, etc.), so they are
  // reported as DecimalType here instead of the narrower type the base transformer would use.

  @Test
  public void testBooleanMapsToDecimalOne() {
    Assertions.assertEquals(
        DecimalType.createDecimalType(1, 0), transformer.getTrinoType(Types.BooleanType.get()));
  }

  @Test
  public void testByteMapsToDecimalThree() {
    Assertions.assertEquals(
        DecimalType.createDecimalType(3, 0), transformer.getTrinoType(Types.ByteType.get()));
  }

  @Test
  public void testShortMapsToDecimalFive() {
    Assertions.assertEquals(
        DecimalType.createDecimalType(5, 0), transformer.getTrinoType(Types.ShortType.get()));
  }

  @Test
  public void testIntegerMapsToDecimalTen() {
    Assertions.assertEquals(
        DecimalType.createDecimalType(10, 0), transformer.getTrinoType(Types.IntegerType.get()));
  }

  @Test
  public void testLongMapsToDecimalNineteen() {
    Assertions.assertEquals(
        DecimalType.createDecimalType(19, 0), transformer.getTrinoType(Types.LongType.get()));
  }

  @Test
  public void testTimestampWithTimeZoneCapsPrecisionAtThree() {
    Assertions.assertEquals(
        TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3),
        transformer.getTrinoType(Types.TimestampType.withTimeZone(6)));
  }

  @Test
  public void testTimestampWithTimeZoneKeepsLowerPrecision() {
    Assertions.assertEquals(
        TimestampWithTimeZoneType.createTimestampWithTimeZoneType(2),
        transformer.getTrinoType(Types.TimestampType.withTimeZone(2)));
  }

  @Test
  public void testTimestampWithoutTimeZonePassthroughToTrino() {
    Assertions.assertEquals(
        io.trino.spi.type.TimestampType.createTimestampType(6),
        transformer.getTrinoType(Types.TimestampType.withoutTimeZone(6)));
  }

  // ---- getTrinoType: passthrough via super ----

  @Test
  public void testDecimalPassthroughToTrino() {
    Assertions.assertEquals(
        DecimalType.createDecimalType(10, 2),
        transformer.getTrinoType(Types.DecimalType.of(10, 2)));
  }

  // ---- getGravitinoType: Oracle-specific overrides ----

  @Test
  public void testUnboundedVarcharMapsToStringType() {
    Assertions.assertEquals(
        Types.StringType.get(),
        transformer.getGravitinoType(VarcharType.createUnboundedVarcharType()));
  }

  @Test
  public void testBoundedVarcharMapsToVarCharType() {
    Assertions.assertEquals(
        Types.VarCharType.of(200),
        transformer.getGravitinoType(VarcharType.createVarcharType(200)));
  }

  @Test
  public void testDateTypeMapsToTimestampWithoutTimeZone() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(3), transformer.getGravitinoType(DateType.DATE));
  }

  @Test
  public void testTimeTypeThrowsTrinoException() {
    Assertions.assertThrows(
        TrinoException.class, () -> transformer.getGravitinoType(TimeType.createTimeType(0)));
  }

  // ---- getGravitinoType: passthrough via super ----

  @Test
  public void testIntegerPassthroughToGravitino() {
    Assertions.assertEquals(
        Types.IntegerType.get(), transformer.getGravitinoType(IntegerType.INTEGER));
  }

  @Test
  public void testCharPassthroughToGravitino() {
    Assertions.assertEquals(
        Types.FixedCharType.of(5), transformer.getGravitinoType(CharType.createCharType(5)));
  }
}
