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
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleDataTypeTransformer {

  private final GeneralDataTypeTransformer transformer = new OracleDataTypeTransformer();

  // ---- getTrinoType: passthrough via super ----

  @Test
  public void testIntegerPassthroughToTrino() {
    Assertions.assertEquals(IntegerType.INTEGER, transformer.getTrinoType(Types.IntegerType.get()));
  }

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
