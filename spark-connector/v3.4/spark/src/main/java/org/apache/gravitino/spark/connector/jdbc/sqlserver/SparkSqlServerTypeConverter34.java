/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.spark.connector.jdbc.sqlserver;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTypeConverter34;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.TimestampType;

/**
 * SQL Server-specific type converter for Spark 3.4/3.5, mirroring {@link
 * SparkSqlServerTypeConverter} on top of {@link SparkJdbcTypeConverter34} instead of the base
 * {@code SparkJdbcTypeConverter}, since {@code GravitinoSqlServerCatalogSpark34}/{@code
 * GravitinoSqlServerCatalogSpark35} override {@code getSparkTypeConverter()} with the 3.4-specific
 * converter.
 *
 * <p>{@link SparkJdbcTypeConverter34}'s parent {@code SparkTypeConverter34} already maps Spark
 * 3.4+'s {@code TimestampNTZType} to {@code Types.TimestampType.withoutTimeZone()}, so only the
 * classic, timezone-aware {@link TimestampType} needs the same override as {@link
 * SparkSqlServerTypeConverter}.
 */
public class SparkSqlServerTypeConverter34 extends SparkJdbcTypeConverter34 {

  @Override
  public Type toGravitinoType(DataType sparkType) {
    if (sparkType instanceof TimestampType) {
      return Types.TimestampType.withoutTimeZone();
    }
    return super.toGravitinoType(sparkType);
  }
}
