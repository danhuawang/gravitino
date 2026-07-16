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
package org.apache.gravitino.trino.connector.catalog.jdbc.sqlserver;

import io.trino.spi.TrinoException;
import io.trino.spi.type.CharType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSqlServerDataTypeTransformer {

  private final SqlServerDataTypeTransformer transformer = new SqlServerDataTypeTransformer();

  @Test
  public void testFixedTypeToTrinoType() {
    Type trinoType = transformer.getTrinoType(Types.FixedType.of(10));
    Assertions.assertEquals(VarbinaryType.VARBINARY, trinoType);
  }

  @Test
  public void testExternalTypeToTrinoType() {
    Type moneyType = transformer.getTrinoType(Types.ExternalType.of("money"));
    Assertions.assertInstanceOf(VarcharType.class, moneyType);
    Assertions.assertTrue(((VarcharType) moneyType).getLength().isEmpty());

    Type xmlType = transformer.getTrinoType(Types.ExternalType.of("xml"));
    Assertions.assertInstanceOf(VarcharType.class, xmlType);
    Assertions.assertTrue(((VarcharType) xmlType).getLength().isEmpty());
  }

  @Test
  public void testTimestampWithTimeZoneToTrinoType() {
    Assertions.assertThrows(
        TrinoException.class, () -> transformer.getTrinoType(Types.TimestampType.withTimeZone()));
  }

  @Test
  public void testTimestampWithoutTimeZoneToTrinoType() {
    Type trinoType = transformer.getTrinoType(Types.TimestampType.withoutTimeZone());
    Assertions.assertInstanceOf(TimestampType.class, trinoType);
  }

  @Test
  public void testCharTypeWithZeroLengthToGravitinoType() {
    Assertions.assertThrows(
        TrinoException.class, () -> transformer.getGravitinoType(CharType.createCharType(0)));
  }

  @Test
  public void testCharTypeToGravitinoType() {
    Assertions.assertEquals(
        Types.FixedCharType.of(10), transformer.getGravitinoType(CharType.createCharType(10)));
  }

  @Test
  public void testUnboundedVarcharTypeToGravitinoType() {
    Assertions.assertEquals(
        Types.StringType.get(),
        transformer.getGravitinoType(VarcharType.createUnboundedVarcharType()));
  }

  @Test
  public void testVarcharTypeToGravitinoType() {
    Assertions.assertEquals(
        Types.VarCharType.of(20), transformer.getGravitinoType(VarcharType.createVarcharType(20)));
  }

  @Test
  public void testIntegerTypeRoundTrip() {
    Assertions.assertEquals(IntegerType.INTEGER, transformer.getTrinoType(Types.IntegerType.get()));
    Assertions.assertEquals(
        Types.IntegerType.get(), transformer.getGravitinoType(IntegerType.INTEGER));
  }
}
