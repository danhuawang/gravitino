/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Type converter for SQL Server catalog. Provides strict one-to-one type mappings. */
public class SqlServerTypeConverter extends JdbcTypeConverter {

  // SQL Server type name constants
  static final String TINYINT = "tinyint";
  static final String SMALLINT = "smallint";
  static final String INT = "int";
  static final String BIGINT = "bigint";
  static final String BIT = "bit";
  static final String DECIMAL = "decimal";
  static final String REAL = "real";
  static final String FLOAT = "float";
  static final String CHAR = "char";
  static final String NVARCHAR = "nvarchar";
  static final String BINARY = "binary";
  static final String VARBINARY = "varbinary";
  static final String UNIQUEIDENTIFIER = "uniqueidentifier";
  static final String DATETIME2 = "datetime2";
  static final String TIME_TYPE = "time";

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    String typeName = typeBean.getTypeName().toLowerCase();
    switch (typeName) {
      case TINYINT:
        return Types.ByteType.get();
      case SMALLINT:
        return Types.ShortType.get();
      case INT:
        return Types.IntegerType.get();
      case BIGINT:
        return Types.LongType.get();
      case BIT:
        return Types.BooleanType.get();
      case DECIMAL:
        return Types.DecimalType.of(typeBean.getColumnSize(), typeBean.getScale());
      case REAL:
        return Types.FloatType.get();
      case FLOAT:
        return Types.DoubleType.get();
      case DATE:
        return Types.DateType.get();
      case TIME_TYPE:
        if (typeBean.getDatetimePrecision() != null) {
          return Types.TimeType.of(typeBean.getDatetimePrecision());
        }
        return Types.TimeType.get();
      case DATETIME2:
        if (typeBean.getDatetimePrecision() != null) {
          return Types.TimestampType.withoutTimeZone(typeBean.getDatetimePrecision());
        }
        return Types.TimestampType.withoutTimeZone();
      case CHAR:
        return Types.FixedCharType.of(typeBean.getColumnSize());
      case VARCHAR:
        // varchar(max) maps to ExternalType
        if (typeBean.getColumnSize() != null && typeBean.getColumnSize() == Integer.MAX_VALUE) {
          return Types.ExternalType.of("varchar(max)");
        }
        return Types.VarCharType.of(typeBean.getColumnSize());
      case NVARCHAR:
        return Types.StringType.get();
      case BINARY:
        return Types.FixedType.of(typeBean.getColumnSize());
      case VARBINARY:
        return Types.BinaryType.get();
      case UNIQUEIDENTIFIER:
        return Types.UUIDType.get();
      default:
        return Types.ExternalType.of(buildExternalTypeName(typeBean));
    }
  }

  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.ByteType) {
      return TINYINT;
    } else if (type instanceof Types.ShortType) {
      return SMALLINT;
    } else if (type instanceof Types.IntegerType) {
      return INT;
    } else if (type instanceof Types.LongType) {
      return BIGINT;
    } else if (type instanceof Types.BooleanType) {
      return BIT;
    } else if (type instanceof Types.DecimalType) {
      Types.DecimalType decimalType = (Types.DecimalType) type;
      return DECIMAL + "(" + decimalType.precision() + "," + decimalType.scale() + ")";
    } else if (type instanceof Types.FloatType) {
      return REAL;
    } else if (type instanceof Types.DoubleType) {
      return FLOAT;
    } else if (type instanceof Types.DateType) {
      return DATE;
    } else if (type instanceof Types.TimeType) {
      Types.TimeType timeType = (Types.TimeType) type;
      return timeType.hasPrecisionSet() ? TIME_TYPE + "(" + timeType.precision() + ")" : TIME_TYPE;
    } else if (type instanceof Types.TimestampType) {
      Types.TimestampType tsType = (Types.TimestampType) type;
      if (tsType.hasTimeZone()) {
        throw new IllegalArgumentException(
            "SQL Server does not support TimestampType with time zone. "
                + "Use ExternalType(\"datetimeoffset\") instead.");
      }
      return tsType.hasPrecisionSet() ? DATETIME2 + "(" + tsType.precision() + ")" : DATETIME2;
    } else if (type instanceof Types.FixedCharType) {
      return CHAR + "(" + ((Types.FixedCharType) type).length() + ")";
    } else if (type instanceof Types.VarCharType) {
      return VARCHAR + "(" + ((Types.VarCharType) type).length() + ")";
    } else if (type instanceof Types.StringType) {
      return NVARCHAR + "(max)";
    } else if (type instanceof Types.FixedType) {
      return BINARY + "(" + ((Types.FixedType) type).length() + ")";
    } else if (type instanceof Types.BinaryType) {
      return VARBINARY + "(max)";
    } else if (type instanceof Types.UUIDType) {
      return UNIQUEIDENTIFIER;
    } else if (type instanceof Types.ExternalType) {
      return ((Types.ExternalType) type).catalogString();
    }
    throw new IllegalArgumentException(
        String.format(
            "Couldn't convert Gravitino type %s to SQL Server type", type.simpleString()));
  }

  private String buildExternalTypeName(JdbcTypeBean typeBean) {
    String typeName = typeBean.getTypeName().toLowerCase();
    // Types with precision/scale parameters
    if (hasParameters(typeName)) {
      if (typeBean.getScale() != null && typeBean.getScale() > 0) {
        return typeName + "(" + typeBean.getColumnSize() + "," + typeBean.getScale() + ")";
      }
      if (typeBean.getColumnSize() != null && typeBean.getColumnSize() > 0) {
        return typeName + "(" + typeBean.getColumnSize() + ")";
      }
    }
    return typeName;
  }

  private boolean hasParameters(String typeName) {
    switch (typeName) {
      case "numeric":
      case "nchar":
      case "datetimeoffset":
        return true;
      default:
        return false;
    }
  }
}
