/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.spark.connector.jdbc.oracle;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTypeConverter;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DateType;

/**
 * Type converter for the Oracle catalog.
 *
 * <p>Oracle has no pure date type: its {@code DATE} column stores both date and time, so {@code
 * OracleTypeConverter} rejects the semantically lossy {@link Types.DateType}. Spark's {@link
 * DateType} is mapped to {@code TimestampType.withoutTimeZone(3)} instead, matching how the Trino
 * connector's {@code OracleDataTypeTransformer} handles the same case, so {@code
 * OracleTypeConverter} emits {@code TIMESTAMP(3)} DDL and both engines round-trip the value with
 * the same precision.
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
