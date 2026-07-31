/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.spark.connector.jdbc.sqlserver;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTypeConverter;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.TimestampType;

/**
 * Type converter for the SQL Server catalog.
 *
 * <p>SQL Server's {@code datetime2} column stores a plain timestamp with no offset, so {@code
 * SqlServerTypeConverter} rejects the timezone-aware {@link Types.TimestampType}. Spark's {@link
 * TimestampType} ("timestamp" in Spark SQL) is mapped to {@code
 * Types.TimestampType.withoutTimeZone()} instead.
 */
public class SparkSqlServerTypeConverter extends SparkJdbcTypeConverter {

  @Override
  public Type toGravitinoType(DataType sparkType) {
    if (sparkType instanceof TimestampType) {
      return Types.TimestampType.withoutTimeZone();
    }
    return super.toGravitinoType(sparkType);
  }
}
