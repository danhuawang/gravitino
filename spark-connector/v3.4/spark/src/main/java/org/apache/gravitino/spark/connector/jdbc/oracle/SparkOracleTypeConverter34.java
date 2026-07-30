/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.spark.connector.jdbc.oracle;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTypeConverter34;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DateType;

/**
 * Oracle-specific type converter for Spark 3.4/3.5, mirroring {@link SparkOracleTypeConverter} on
 * top of {@link SparkJdbcTypeConverter34} instead of the base {@code SparkJdbcTypeConverter}, since
 * {@code GravitinoOracleCatalogSpark34}/{@code GravitinoOracleCatalogSpark35} override {@code
 * getSparkTypeConverter()} with the 3.4-specific converter.
 */
public class SparkOracleTypeConverter34 extends SparkJdbcTypeConverter34 {

  @Override
  public Type toGravitinoType(DataType sparkType) {
    if (sparkType instanceof DateType) {
      return Types.TimestampType.withoutTimeZone(3);
    }
    return super.toGravitinoType(sparkType);
  }

  @Override
  public DataType toSparkType(Type gravitinoType) {
    if (gravitinoType instanceof Types.ExternalType) {
      return DataTypes.StringType;
    }
    return super.toSparkType(gravitinoType);
  }
}
