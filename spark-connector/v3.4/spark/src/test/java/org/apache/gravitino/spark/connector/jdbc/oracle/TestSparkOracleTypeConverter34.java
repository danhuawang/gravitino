/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.jdbc.oracle;

import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSparkOracleTypeConverter34 {

  private final SparkTypeConverter sparkOracleTypeConverter = new SparkOracleTypeConverter34();

  @Test
  void testDateTypeMapsToTimestampWithMillisPrecision() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(3),
        sparkOracleTypeConverter.toGravitinoType(DataTypes.DateType));
  }

  @Test
  void testTimestampTypeMapsToTimestampWithoutTimeZone() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(),
        sparkOracleTypeConverter.toGravitinoType(DataTypes.TimestampType));
  }

  @Test
  void testExternalTypeMapsToStringType() {
    Assertions.assertEquals(
        DataTypes.StringType,
        sparkOracleTypeConverter.toSparkType(Types.ExternalType.of("NUMBER")));
  }

  @Test
  void testOtherTypesFallBackTo34Converter() {
    Assertions.assertEquals(
        DataTypes.StringType, sparkOracleTypeConverter.toSparkType(Types.VarCharType.of(10)));
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkOracleTypeConverter.toSparkType(Types.TimestampType.withoutTimeZone()));
  }
}
