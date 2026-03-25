/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/**
 * Type converter for MaxCompute.
 *
 * <p>Supported type mappings:
 *
 * <ul>
 *   <li>TINYINT, SMALLINT, INT, BIGINT - Integer types
 *   <li>FLOAT, DOUBLE - Floating point types
 *   <li>DECIMAL(p,s) - Decimal with precision (1-38) and scale (0-18)
 *   <li>BOOLEAN - Boolean type
 *   <li>STRING, VARCHAR(n), CHAR(n) - String types
 *   <li>BINARY - Binary data
 *   <li>DATE, DATETIME, TIMESTAMP - Date/time types
 *   <li>ARRAY, MAP, STRUCT, JSON - Complex types (mapped to ExternalType)
 *   <li>INTERVAL_YEAR_MONTH, INTERVAL_DAY_TIME - Interval types
 * </ul>
 */
public class MaxComputeTypeConverter extends JdbcTypeConverter {

  // MaxCompute basic types
  static final String TINYINT = "tinyint";
  static final String SMALLINT = "smallint";
  static final String INT = "int";
  static final String BIGINT = "bigint";
  static final String FLOAT = "float";
  static final String DOUBLE = "double";
  static final String DECIMAL = "decimal";
  static final String BOOLEAN = "boolean";

  // MaxCompute string types
  static final String STRING = "string";
  static final String VARCHAR = "varchar";
  static final String CHAR = "char";

  // MaxCompute binary type
  static final String BINARY = "binary";

  // MaxCompute date/time types
  static final String DATE = "date";
  static final String DATETIME = "datetime";
  static final String TIMESTAMP = "timestamp";
  static final String TIMESTAMP_NTZ = "timestamp_ntz";

  // MaxCompute complex types (handled as external types)
  static final String ARRAY = "array";
  static final String MAP = "map";
  static final String STRUCT = "struct";
  static final String JSON = "json";

  // MaxCompute interval types (handled as external types)
  static final String INTERVAL_YEAR_MONTH = "interval_year_month";
  static final String INTERVAL_DAY_TIME = "interval_day_time";

  // MaxCompute void type
  static final String VOID = "void";

  // MaxCompute limits
  private static final int MAX_DECIMAL_PRECISION = 38;
  private static final int DEFAULT_MAX_DECIMAL_SCALE = 18;
  private static final int MAX_VARCHAR_LENGTH = 65535;
  private static final int MAX_CHAR_LENGTH = 255;

  /**
   * Converts a MaxCompute JDBC type to a Gravitino type.
   *
   * @param typeBean the JDBC type bean containing type information
   * @return the corresponding Gravitino type
   */
  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    String typeName = typeBean.getTypeName().toLowerCase();

    switch (typeName) {
      case BOOLEAN:
        return Types.BooleanType.get();

      case TINYINT:
        return Types.ByteType.get();

      case SMALLINT:
        return Types.ShortType.get();

      case INT:
        return Types.IntegerType.get();

      case BIGINT:
        return Types.LongType.get();

      case FLOAT:
        return Types.FloatType.get();

      case DOUBLE:
        return Types.DoubleType.get();

      case DECIMAL:
        // MaxCompute DECIMAL: precision 1-38, scale 0-18 (default) or 0-38 (extended)
        int precision = typeBean.getColumnSize();
        int scale = typeBean.getScale();
        // Ensure precision is within valid range
        // When reading from JDBC metadata, we cap invalid values to be lenient
        if (precision <= 0) {
          precision = MAX_DECIMAL_PRECISION;
        }
        if (precision > MAX_DECIMAL_PRECISION) {
          precision = MAX_DECIMAL_PRECISION;
        }
        // Ensure scale is within valid range
        // Note: We don't truncate scale here to preserve the original type information
        // If scale > 18, it means the extended scale mode is enabled in MaxCompute
        if (scale < 0) {
          scale = 0;
        }
        if (scale > MAX_DECIMAL_PRECISION) {
          scale = MAX_DECIMAL_PRECISION;
        }
        // Scale cannot exceed precision
        if (scale > precision) {
          scale = precision;
        }
        return Types.DecimalType.of(precision, scale);

      case STRING:
      case TEXT:
        return Types.StringType.get();

      case VARCHAR:
        int varcharLength = typeBean.getColumnSize();
        if (varcharLength <= 0 || varcharLength > MAX_VARCHAR_LENGTH) {
          // If length is invalid, treat as STRING
          return Types.StringType.get();
        }
        return Types.VarCharType.of(varcharLength);

      case CHAR:
        int charLength = typeBean.getColumnSize();
        if (charLength <= 0 || charLength > MAX_CHAR_LENGTH) {
          // If length is invalid, use max length
          charLength = MAX_CHAR_LENGTH;
        }
        return Types.FixedCharType.of(charLength);

      case BINARY:
        return Types.BinaryType.get();

      case DATE:
        return Types.DateType.get();

      case DATETIME:
        // DATETIME in MaxCompute has millisecond precision (3 decimal places)
        // Maps to Gravitino TimestampType without timezone with precision 3
        return Types.TimestampType.withoutTimeZone(3);

      case TIMESTAMP:
      case TIMESTAMP_NTZ:
        // TIMESTAMP in MaxCompute has nanosecond precision (9 decimal places)
        // TIMESTAMP_NTZ is timestamp without timezone
        // Both map to Gravitino TimestampType without timezone with precision 9
        return Types.TimestampType.withoutTimeZone(9);

      case VOID:
        return Types.NullType.get();

      default:
        // Handle complex types (ARRAY, MAP, STRUCT, JSON) and interval types
        // as external types to preserve the original type information.
        // Any type that reaches here (including unknown types) is treated as ExternalType.
        return Types.ExternalType.of(typeBean.getTypeName());
    }
  }

  /**
   * Converts a Gravitino type to a MaxCompute SQL type string.
   *
   * @param type the Gravitino type
   * @return the corresponding MaxCompute SQL type string
   * @throws IllegalArgumentException if the type is not supported by MaxCompute
   */
  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.BooleanType) {
      return BOOLEAN;
    } else if (type instanceof Types.ByteType) {
      return TINYINT;
    } else if (type instanceof Types.ShortType) {
      return SMALLINT;
    } else if (type instanceof Types.IntegerType) {
      return INT;
    } else if (type instanceof Types.LongType) {
      return BIGINT;
    } else if (type instanceof Types.FloatType) {
      return FLOAT;
    } else if (type instanceof Types.DoubleType) {
      return DOUBLE;
    } else if (type instanceof Types.DecimalType decimalType) {
      validateDecimalType(decimalType);
      return "%s(%d,%d)".formatted(DECIMAL, decimalType.precision(), decimalType.scale());
    } else if (type instanceof Types.StringType) {
      return STRING;
    } else if (type instanceof Types.VarCharType varCharType) {
      validateVarCharType(varCharType);
      return "%s(%d)".formatted(VARCHAR, varCharType.length());
    } else if (type instanceof Types.FixedCharType charType) {
      validateCharType(charType);
      return "%s(%d)".formatted(CHAR, charType.length());
    } else if (type instanceof Types.BinaryType) {
      return BINARY;
    } else if (type instanceof Types.DateType) {
      return DATE;
    } else if (type instanceof Types.TimestampType) {
      // MaxCompute TIMESTAMP does not have timezone concept
      // Both withTimeZone and withoutTimeZone map to TIMESTAMP
      return TIMESTAMP;
    } else if (type instanceof Types.TimeType) {
      // MaxCompute does not support TIME type
      throw new IllegalArgumentException(
          "MaxCompute does not support TIME type. Consider using DATETIME or STRING instead.");
    } else if (type instanceof Types.IntervalYearType) {
      // MaxCompute has INTERVAL_YEAR_MONTH type
      return INTERVAL_YEAR_MONTH;
    } else if (type instanceof Types.IntervalDayType) {
      // MaxCompute has INTERVAL_DAY_TIME type
      return INTERVAL_DAY_TIME;
    } else if (type instanceof Types.NullType) {
      return VOID;
    } else if (type instanceof Types.UUIDType) {
      // MaxCompute does not support UUID type
      throw new IllegalArgumentException(
          "MaxCompute does not support UUID type. Consider using STRING instead.");
    } else if (type instanceof Types.FixedType) {
      // MaxCompute does not support FIXED type
      throw new IllegalArgumentException(
          "MaxCompute does not support FIXED type. Consider using BINARY instead.");
    } else if (type instanceof Types.ListType
        || type instanceof Types.MapType
        || type instanceof Types.StructType) {
      // Complex types should be handled as ExternalType
      throw new IllegalArgumentException(
          ("Complex type %s should be created using ExternalType with MaxCompute syntax. "
                  + "Example: Types.ExternalType.of(\"ARRAY<STRING>\")")
              .formatted(type.simpleString()));
    } else if (type instanceof Types.ExternalType) {
      // Return the original type string for external types
      return ((Types.ExternalType) type).catalogString();
    } else if (type instanceof Types.UnparsedType) {
      // Return the unparsed type string
      return ((Types.UnparsedType) type).unparsedType();
    }

    throw new IllegalArgumentException(
        "Couldn't convert Gravitino type %s to MaxCompute type".formatted(type.simpleString()));
  }

  /**
   * Validates the DECIMAL type precision and scale.
   *
   * @param decimalType the decimal type to validate
   * @throws IllegalArgumentException if precision or scale is out of range
   */
  private void validateDecimalType(Types.DecimalType decimalType) {
    int precision = decimalType.precision();
    int scale = decimalType.scale();

    if (precision < 1 || precision > MAX_DECIMAL_PRECISION) {
      throw new IllegalArgumentException(
          "MaxCompute DECIMAL precision must be between 1 and %d, but got %d"
              .formatted(MAX_DECIMAL_PRECISION, precision));
    }

    // Default max scale is 18, but can be extended to 38 with flag
    // We use the default limit here for safety
    if (scale < 0 || scale > DEFAULT_MAX_DECIMAL_SCALE) {
      throw new IllegalArgumentException(
          ("MaxCompute DECIMAL scale must be between 0 and %d (default), but got %d. "
                  + "Extended scale up to 38 requires setting odps.sql.decimal2.extended.scale.enable=true")
              .formatted(DEFAULT_MAX_DECIMAL_SCALE, scale));
    }

    if (scale > precision) {
      throw new IllegalArgumentException(
          "MaxCompute DECIMAL scale (%d) cannot be greater than precision (%d)"
              .formatted(scale, precision));
    }
  }

  /**
   * Validates the VARCHAR type length.
   *
   * @param varCharType the varchar type to validate
   * @throws IllegalArgumentException if length is out of range
   */
  private void validateVarCharType(Types.VarCharType varCharType) {
    int length = varCharType.length();
    if (length < 1 || length > MAX_VARCHAR_LENGTH) {
      throw new IllegalArgumentException(
          "MaxCompute VARCHAR length must be between 1 and %d, but got %d"
              .formatted(MAX_VARCHAR_LENGTH, length));
    }
  }

  /**
   * Validates the CHAR type length.
   *
   * @param charType the char type to validate
   * @throws IllegalArgumentException if length is out of range
   */
  private void validateCharType(Types.FixedCharType charType) {
    int length = charType.length();
    if (length < 1 || length > MAX_CHAR_LENGTH) {
      throw new IllegalArgumentException(
          "MaxCompute CHAR length must be between 1 and %d, but got %d"
              .formatted(MAX_CHAR_LENGTH, length));
    }
  }
}
