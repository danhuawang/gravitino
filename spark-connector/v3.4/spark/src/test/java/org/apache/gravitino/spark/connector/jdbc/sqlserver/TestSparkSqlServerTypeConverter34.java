/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.jdbc.sqlserver;

import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSparkSqlServerTypeConverter34 {

  private final SparkTypeConverter sparkSqlServerTypeConverter =
      new SparkSqlServerTypeConverter34();

  @Test
  void testTimestampTypeMapsToTimestampWithoutTimeZone() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(),
        sparkSqlServerTypeConverter.toGravitinoType(DataTypes.TimestampType));
  }

  @Test
  void testTimestampNtzTypeMapsToTimestampWithoutTimeZone() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(),
        sparkSqlServerTypeConverter.toGravitinoType(DataTypes.TimestampNTZType));
  }

  @Test
  void testOtherTypesFallBackTo34Converter() {
    Assertions.assertEquals(
        Types.IntegerType.get(),
        sparkSqlServerTypeConverter.toGravitinoType(DataTypes.IntegerType));
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkSqlServerTypeConverter.toSparkType(Types.TimestampType.withoutTimeZone()));
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkSqlServerTypeConverter.toSparkType(Types.TimestampType.withTimeZone()));
  }
}
