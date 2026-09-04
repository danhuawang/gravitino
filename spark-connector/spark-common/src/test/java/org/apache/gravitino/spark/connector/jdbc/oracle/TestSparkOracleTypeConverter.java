/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.jdbc.oracle;

import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSparkOracleTypeConverter {

  private final SparkTypeConverter sparkOracleTypeConverter = new SparkOracleTypeConverter();

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
  void testOtherTypesFallBackToJdbcConverter() {
    Assertions.assertEquals(
        Types.IntegerType.get(), sparkOracleTypeConverter.toGravitinoType(DataTypes.IntegerType));
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkOracleTypeConverter.toSparkType(Types.TimestampType.withoutTimeZone(3)));
  }

  @Test
  void testExternalTypeMapsToStringType() {
    Assertions.assertEquals(
        DataTypes.StringType,
        sparkOracleTypeConverter.toSparkType(Types.ExternalType.of("NUMBER")));
    Assertions.assertEquals(
        DataTypes.StringType,
        sparkOracleTypeConverter.toSparkType(Types.ExternalType.of("NCHAR(10)")));
  }
}
