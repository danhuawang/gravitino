/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery.converter;

import java.util.Optional;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Type converter for BigQuery. */
public class BigQueryTypeConverter extends JdbcTypeConverter {

  // BigQuery type names
  static final String BOOL = "bool";
  static final String BOOLEAN = "boolean";
  static final String INT64 = "int64";
  static final String FLOAT64 = "float64";
  static final String NUMERIC = "numeric";
  static final String BIGNUMERIC = "bignumeric";
  static final String STRING = "string";
  static final String BYTES = "bytes";
  static final String DATE = "date";
  static final String DATETIME = "datetime";
  static final String TIME = "time";
  static final String TIMESTAMP = "timestamp";
  static final String GEOGRAPHY = "geography";
  static final String JSON = "json";
  static final String STRUCT = "struct";
  static final String RANGE = "range";

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    String typeName = typeBean.getTypeName().toLowerCase();

    switch (typeName) {
      case BOOL:
      case BOOLEAN: // BigQuery may return "BOOLEAN" which becomes "boolean" in lowercase
        return Types.BooleanType.get();
      case INT64:
        return Types.LongType.get();
      case FLOAT64:
        return Types.DoubleType.get();
      case NUMERIC:
        // BigQuery NUMERIC has precision 38, scale 9 by default
        Integer precision = typeBean.getColumnSize();
        Integer scale = typeBean.getScale();
        if (precision != null && scale != null) {
          return Types.DecimalType.of(precision, scale);
        } else {
          return Types.DecimalType.of(38, 9);
        }
      case STRING:
        return Types.StringType.get();
      case BYTES:
        return Types.BinaryType.get();
      case DATE:
        return Types.DateType.get();
      case TIME:
        return Optional.ofNullable(typeBean.getDatetimePrecision())
            .map(Types.TimeType::of)
            .orElseGet(Types.TimeType::get);
      case DATETIME:
        return Optional.ofNullable(typeBean.getDatetimePrecision())
            .map(Types.TimestampType::withoutTimeZone)
            .orElseGet(Types.TimestampType::withoutTimeZone);
      case TIMESTAMP:
        return Optional.ofNullable(typeBean.getDatetimePrecision())
            .map(Types.TimestampType::withTimeZone)
            .orElseGet(Types.TimestampType::withTimeZone);
      case BIGNUMERIC:
      case GEOGRAPHY:
      case JSON:
      case STRUCT:
      case RANGE:
        // Handle BIGNUMERIC, GEOGRAPHY, JSON, STRUCT, and RANGE as external types
        // BIGNUMERIC: Avoid precision loss (BigQuery supports approximately 76.8 digits,
        // Gravitino Decimal only up to 38)
        // GEOGRAPHY, JSON, STRUCT, RANGE: Complex types not directly supported by Gravitino
        // Preserve the full type definition with uppercase type name
        return Types.ExternalType.of(typeBean.getTypeName().toUpperCase());
      default:
        // For complex types like ARRAY<T>, STRUCT<...>, RANGE<T>, preserve the full type
        // definition
        // The typeName from JDBC should contain the complete type like "ARRAY<STRING>"
        // We need to preserve this for proper SQL generation
        return Types.ExternalType.of(typeBean.getTypeName().toUpperCase());
    }
  }

  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.BooleanType) {
      return BOOL;
    } else if (type instanceof Types.ByteType) {
      // BigQuery doesn't have a direct byte type, map to INT64
      return INT64;
    } else if (type instanceof Types.ShortType) {
      // BigQuery doesn't have a direct short type, map to INT64
      return INT64;
    } else if (type instanceof Types.IntegerType) {
      // BigQuery doesn't have a direct int type, map to INT64
      return INT64;
    } else if (type instanceof Types.LongType) {
      return INT64;
    } else if (type instanceof Types.FloatType) {
      // BigQuery doesn't have a direct float type, map to FLOAT64
      return FLOAT64;
    } else if (type instanceof Types.DoubleType) {
      return FLOAT64;
    } else if (type instanceof Types.StringType) {
      return STRING;
    } else if (type instanceof Types.VarCharType) {
      // BigQuery STRING type is variable length
      return STRING;
    } else if (type instanceof Types.FixedCharType) {
      // BigQuery doesn't have fixed char, use STRING
      return STRING;
    } else if (type instanceof Types.BinaryType) {
      return BYTES;
    } else if (type instanceof Types.DateType) {
      return DATE;
    } else if (type instanceof Types.TimeType) {
      return TIME;
    } else if (type instanceof Types.TimestampType timestampType) {
      return timestampType.hasTimeZone() ? TIMESTAMP : DATETIME;
    } else if (type instanceof Types.DecimalType decimalType) {
      // BigQuery NUMERIC: precision 1-38, scale 0-9 (or 0-precision)
      // For Gravitino DecimalType (max precision 38), always use NUMERIC
      return String.format("NUMERIC(%d, %d)", decimalType.precision(), decimalType.scale());
    } else if (type instanceof Types.ExternalType) {
      return ((Types.ExternalType) type).catalogString();
    } else if (type instanceof Types.UnparsedType unparsedType) {
      // Handle unparsed types from Web UI
      String unparsedStr = unparsedType.unparsedType().toLowerCase();

      // Map common unparsed type names to BigQuery types
      // This handles cases where Web UI sends lowercase type names or
      // when users specify types through API calls
      switch (unparsedStr) {
        case "bool": // From Web UI type selection or API calls
          return BOOL;
        case "int64": // From Web UI type selection or API calls
          return INT64;
        case "float64": // From Web UI type selection or API calls
          return FLOAT64;
        case "string": // From Web UI type selection or API calls
          return STRING;
        case "bytes": // From Web UI type selection or API calls
          return BYTES;
        case "date": // From Web UI type selection or API calls
          return DATE;
        case "time": // From Web UI type selection or API calls
          return TIME;
        case "datetime": // From Web UI type selection or API calls
          return DATETIME;
        case "timestamp": // From Web UI type selection or API calls
          return TIMESTAMP;
        case "numeric": // From Web UI type selection or API calls
          return NUMERIC;
        case "bignumeric": // From Web UI type selection or API calls
          return BIGNUMERIC;
        default:
          // For complete complex types like ARRAY<INT64>, STRUCT<...>, RANGE<DATE>, return as-is
          // These come from API calls or direct type specifications, not from Web UI
          if (unparsedStr.startsWith("array<")
              || unparsedStr.startsWith("struct<")
              || unparsedStr.startsWith("range<")) {
            return unparsedStr;
          }

          // Return as-is for other unparsed types
          return unparsedStr;
      }
    }

    throw new IllegalArgumentException(
        String.format("Couldn't convert Gravitino type %s to BigQuery type", type.simpleString()));
  }
}
