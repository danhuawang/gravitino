/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import java.util.Locale;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Type converter for Oracle. */
public class OracleTypeConverter extends JdbcTypeConverter {

  static final String NUMBER = "NUMBER";
  static final String VARCHAR2 = "VARCHAR2";
  static final String CHAR = "CHAR";
  static final String NVARCHAR2 = "NVARCHAR2";
  static final String NCHAR = "NCHAR";
  static final String CLOB = "CLOB";
  static final String NCLOB = "NCLOB";
  static final String BLOB = "BLOB";
  static final String FLOAT = "FLOAT";
  static final String BINARY_FLOAT = "BINARY_FLOAT";
  static final String BINARY_DOUBLE = "BINARY_DOUBLE";
  static final String RAW = "RAW";
  static final String LONG_RAW = "LONG RAW";
  static final String TIMESTAMP = "TIMESTAMP";
  static final String TIMESTAMP_WITH_TIME_ZONE = "TIMESTAMP WITH TIME ZONE";
  static final String TIMESTAMP_WITH_LOCAL_TIME_ZONE = "TIMESTAMP WITH LOCAL TIME ZONE";
  static final String DATE = "DATE";
  static final String BOOLEAN = "BOOLEAN";

  private static final int ORACLE_MAX_DECIMAL_PRECISION = 38;
  private static final int UNCONSTRAINED_NUMBER_SCALE = -127;

  private boolean nativeBooleanSupported;

  /**
   * Configures whether the connected Oracle database supports the native SQL {@code BOOLEAN} type.
   *
   * @param nativeBooleanSupported whether native SQL booleans are supported
   */
  public void setNativeBooleanSupported(boolean nativeBooleanSupported) {
    this.nativeBooleanSupported = nativeBooleanSupported;
  }

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    String typeName = typeBean.getTypeName().toUpperCase(Locale.ROOT);
    Integer precision = typeBean.getColumnSize();
    Integer scale = typeBean.getScale();

    if (typeName.startsWith(TIMESTAMP)) {
      return toGravitinoTimestampType(typeName, scale);
    }

    switch (typeName) {
      case NUMBER:
        if (precision == null && scale == null) {
          return Types.ExternalType.of(NUMBER);
        }
        // Oracle JDBC exposes an unconstrained NUMBER as precision=0 and scale=-127. These are
        // metadata sentinels rather than a valid API decimal declaration.
        if (safeInt(precision, 0) == 0 && safeInt(scale, 0) == UNCONSTRAINED_NUMBER_SCALE) {
          return Types.DecimalType.of(ORACLE_MAX_DECIMAL_PRECISION, 0);
        }
        if (scale != null && scale < 0) {
          return Types.ExternalType.of(
              String.format(
                  "NUMBER(%d,%d)", safeInt(precision, ORACLE_MAX_DECIMAL_PRECISION), scale));
        }
        if (scale != null && scale > 0) {
          return Types.DecimalType.of(decimalPrecision(precision), scale);
        }
        // NUMBER(p) and NUMBER(p,0) map to Decimal(p,0), preserving the declared precision (as the
        // PostgreSQL connector does for NUMERIC). Bucketing scale-0 NUMBER into fixed Java integer
        // widths (byte/short/integer/long) by precision discarded the declared precision and, at
        // some boundaries, reported a type too narrow for the values the source column permits
        // (e.g. NUMBER(5,0) allows 99999 but short caps at 32767). Reporting the source accurately
        // is preferred over round-tripping Gravitino integer type names.
        return Types.DecimalType.of(decimalPrecision(precision), 0);
      case VARCHAR2:
      case VARCHAR:
        return precision == null ? Types.StringType.get() : Types.VarCharType.of(precision);
      case CHAR:
        return precision == null ? Types.FixedCharType.of(1) : Types.FixedCharType.of(precision);
      case NCHAR:
        return Types.ExternalType.of(
            precision == null ? NCHAR : String.format("%s(%d)", NCHAR, precision));
      case NVARCHAR2:
        return Types.ExternalType.of(
            precision == null ? NVARCHAR2 : String.format("%s(%d)", NVARCHAR2, precision));
      case CLOB:
      case NCLOB:
        return Types.StringType.get();
      case BLOB:
      case RAW:
      case LONG_RAW:
        return Types.BinaryType.get();
      case FLOAT:
      case BINARY_DOUBLE:
        return Types.DoubleType.get();
      case BINARY_FLOAT:
        return Types.FloatType.get();
      case DATE:
        // Oracle DATE stores date + time to the second (no fractional seconds), so it is not a
        // Gravitino Date. Report it as a second-precision Timestamp: this is the closest type the
        // engine connectors (Spark/Flink/Trino) can actually use as a temporal value, rather than
        // an ExternalType that they would surface as a plain string. Precision 0 mirrors Oracle
        // DATE's second granularity. Round-tripping is handled on the write side: a Gravitino
        // DateType maps back to Oracle DATE (see fromGravitino), which is the reversible path the
        // caller can use to recreate the source type.
        return Types.TimestampType.withoutTimeZone(0);
      case BOOLEAN:
        return Types.BooleanType.get();
      default:
        return Types.ExternalType.of(typeBean.getTypeName());
    }
  }

  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.ByteType) {
      return "NUMBER(3)";
    } else if (type instanceof Types.ShortType) {
      return "NUMBER(5)";
    } else if (type instanceof Types.IntegerType) {
      return "NUMBER(10)";
    } else if (type instanceof Types.LongType) {
      return "NUMBER(19)";
    } else if (type instanceof Types.DecimalType) {
      Types.DecimalType decimalType = (Types.DecimalType) type;
      return String.format("NUMBER(%d,%d)", decimalType.precision(), decimalType.scale());
    } else if (type instanceof Types.FloatType) {
      return BINARY_FLOAT;
    } else if (type instanceof Types.DoubleType) {
      return BINARY_DOUBLE;
    } else if (type instanceof Types.BooleanType) {
      return nativeBooleanSupported ? BOOLEAN : "NUMBER(1)";
    } else if (type instanceof Types.StringType) {
      return CLOB;
    } else if (type instanceof Types.VarCharType) {
      return String.format("%s(%d)", VARCHAR2, ((Types.VarCharType) type).length());
    } else if (type instanceof Types.FixedCharType) {
      return String.format("%s(%d)", CHAR, ((Types.FixedCharType) type).length());
    } else if (type instanceof Types.TimestampType) {
      Types.TimestampType timestampType = (Types.TimestampType) type;
      if (timestampType.hasTimeZone()) {
        if (timestampType.hasPrecisionSet()) {
          return String.format("TIMESTAMP(%d) WITH TIME ZONE", timestampType.precision());
        }
        return "TIMESTAMP(6) WITH TIME ZONE";
      }
      if (timestampType.hasPrecisionSet()) {
        return String.format("TIMESTAMP(%d)", timestampType.precision());
      }
      return "TIMESTAMP(6)";
    } else if (type instanceof Types.BinaryType) {
      return BLOB;
    } else if (type instanceof Types.ExternalType) {
      return ((Types.ExternalType) type).catalogString();
    } else if (type instanceof Types.DateType) {
      // Oracle DATE is a date + time (to the second) value, so it is the natural target for a
      // Gravitino DateType write. Mapping DateType -> DATE (instead of rejecting it) closes the
      // round trip: an Oracle DATE column is read as Timestamp(0) and can be recreated by writing
      // back a DateType. ExternalType("DATE") remains supported for callers that prefer it.
      return DATE;
    }

    throw new UnsupportedOperationException(
        String.format(
            "Oracle does not support converting Gravitino type %s.", type.simpleString()));
  }

  private static int safeInt(Integer value, int defaultValue) {
    return value == null ? defaultValue : value;
  }

  /**
   * Returns a precision valid for a Gravitino {@link Types.DecimalType} (which requires precision
   * in [1, 38]). Oracle JDBC can report a non-positive precision for a {@code NUMBER} whose
   * precision is unknown or unconstrained (e.g. {@code precision = 0} outside the {@code scale =
   * -127} sentinel path); in that case fall back to the maximum Oracle precision rather than
   * passing an out-of-range value to {@code DecimalType.of}, which would throw.
   */
  private static int decimalPrecision(Integer precision) {
    if (precision == null || precision <= 0) {
      return ORACLE_MAX_DECIMAL_PRECISION;
    }
    return Math.min(precision, ORACLE_MAX_DECIMAL_PRECISION);
  }

  private static Type toGravitinoTimestampType(String typeName, Integer scale) {
    // Gravitino TimestampType cannot distinguish "WITH TIME ZONE" from "WITH LOCAL TIME ZONE".
    // Instead of failing the whole table load on this one column, preserve the exact Oracle type
    // name as ExternalType. It stays distinguishable from the plain "WITH TIME ZONE" mapping (whose
    // catalogString differs) and round-trips back to Oracle unchanged.
    if (typeName.contains("WITH LOCAL TIME ZONE")) {
      Integer localTzPrecision = extractTimestampPrecision(typeName, scale);
      return Types.ExternalType.of(
          localTzPrecision == null
              ? TIMESTAMP_WITH_LOCAL_TIME_ZONE
              : String.format("TIMESTAMP(%d) WITH LOCAL TIME ZONE", localTzPrecision));
    }
    boolean hasTimeZone = typeName.contains("WITH TIME ZONE");
    Integer precision = extractTimestampPrecision(typeName, scale);
    if (hasTimeZone) {
      return precision == null
          ? Types.TimestampType.withTimeZone()
          : Types.TimestampType.withTimeZone(precision);
    }
    return precision == null
        ? Types.TimestampType.withoutTimeZone()
        : Types.TimestampType.withoutTimeZone(precision);
  }

  // Oracle JDBC reports the fractional seconds precision in two places:
  //   - TYPE_NAME usually carries it inline, e.g. "TIMESTAMP(6)".
  //   - DECIMAL_DIGITS (mapped to scale) also carries the same value.
  // COLUMN_SIZE is the byte width (e.g. 11 for TIMESTAMP(6)) and must NOT be
  // used as precision. We prefer parsing from the type name and fall back to
  // scale only when the type name has no parentheses.
  private static Integer extractTimestampPrecision(String typeName, Integer scale) {
    int leftParen = typeName.indexOf('(');
    int rightParen = typeName.indexOf(')', leftParen + 1);
    if (leftParen >= 0 && rightParen > leftParen) {
      return Integer.parseInt(typeName.substring(leftParen + 1, rightParen));
    }
    return scale;
  }
}
