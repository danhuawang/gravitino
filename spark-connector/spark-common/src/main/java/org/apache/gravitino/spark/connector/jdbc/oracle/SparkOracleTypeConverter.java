/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.spark.connector.jdbc.oracle;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTypeConverter;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.TimestampType;

/**
 * Type converter for the Oracle catalog.
 *
 * <p>Oracle has no pure date type: its {@code DATE} column stores both date and time, so {@link
 * Types.DateType} is rejected by {@code OracleTypeConverter}. Spark's {@link DateType} is mapped to
 * {@code TimestampType.withoutTimeZone(3)} instead, matching how the Trino connector's {@code
 * OracleDataTypeTransformer} handles the same case, so {@code OracleTypeConverter} emits {@code
 * TIMESTAMP(3)} DDL and both engines round-trip the value with the same precision.
 *
 * <p>The base {@link org.apache.gravitino.spark.connector.SparkTypeConverter} maps Spark's {@link
 * TimestampType} ("timestamp" in Spark SQL) to the timezone-aware {@link Types.TimestampType},
 * which makes {@code OracleTypeConverter} emit {@code TIMESTAMP WITH TIME ZONE} DDL. Oracle's JDBC
 * driver reports that column back as JDBC type {@code TIMESTAMPTZ}, which Spark's own Oracle
 * dialect cannot resolve to a Catalyst type, so the immediate {@code loadTable()} that follows
 * {@code CREATE TABLE} fails with a misleading {@code TABLE_OR_VIEW_NOT_FOUND}. Map it to {@code
 * Types.TimestampType.withoutTimeZone()} instead, mirroring {@link
 * org.apache.gravitino.spark.connector.jdbc.sqlserver.SparkSqlServerTypeConverter}.
 *
 * <p>{@code OracleTypeConverter#toGravitino} also produces {@link Types.ExternalType} for Oracle
 * types that have no Gravitino equivalent (unbounded {@code NUMBER}, {@code NCHAR}, {@code
 * NVARCHAR2}), which the base {@link org.apache.gravitino.spark.connector.SparkTypeConverter}
 * cannot convert back to a Spark type. Map it to {@link DataTypes#StringType}, matching how {@code
 * OracleDataTypeTransformer} maps the same case to an unbounded Trino {@code VarcharType}.
 */
public class SparkOracleTypeConverter extends SparkJdbcTypeConverter {

  @Override
  public Type toGravitinoType(DataType sparkType) {
    if (sparkType instanceof DateType) {
      return Types.TimestampType.withoutTimeZone(3);
    } else if (sparkType instanceof TimestampType) {
      return Types.TimestampType.withoutTimeZone();
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
