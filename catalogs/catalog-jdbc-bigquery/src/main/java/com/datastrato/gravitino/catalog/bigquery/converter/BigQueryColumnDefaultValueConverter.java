/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;

/** Column default value converter for BigQuery. */
public class BigQueryColumnDefaultValueConverter extends JdbcColumnDefaultValueConverter {

  @Override
  public Expression toGravitino(
      JdbcTypeConverter.JdbcTypeBean type,
      String columnDefaultValue,
      boolean isExpression,
      boolean nullable) {
    if (columnDefaultValue == null) {
      return nullable ? Literals.NULL : DEFAULT_VALUE_NOT_SET;
    }

    if (columnDefaultValue.equalsIgnoreCase(NULL)) {
      return Literals.NULL;
    }

    // Handle BigQuery expressions
    if (isExpression) {
      // BigQuery functions like CURRENT_TIMESTAMP(), CURRENT_DATE(), etc.
      // are not supported as literals in Gravitino, return as unparsed expression
      return UnparsedExpression.of(columnDefaultValue);
    }

    // Handle BigQuery specific default value formats
    String trimmedValue = columnDefaultValue.trim();

    // Handle quoted strings
    if (trimmedValue.startsWith("'") && trimmedValue.endsWith("'") && trimmedValue.length() >= 2) {
      String unquoted = trimmedValue.substring(1, trimmedValue.length() - 1);
      return Literals.stringLiteral(unquoted);
    }

    // Handle boolean values (extract to method to avoid duplication)
    Boolean boolValue = parseBooleanValue(trimmedValue);
    if (boolValue != null) {
      return Literals.booleanLiteral(boolValue);
    }

    // Handle numeric values based on BigQuery type
    String typeName = type.getTypeName().toLowerCase();
    switch (typeName) {
      case BigQueryTypeConverter.INT64:
        try {
          return Literals.longLiteral(Long.parseLong(trimmedValue));
        } catch (NumberFormatException e) {
          return UnparsedExpression.of(columnDefaultValue);
        }

      case BigQueryTypeConverter.FLOAT64:
        try {
          return Literals.doubleLiteral(Double.parseDouble(trimmedValue));
        } catch (NumberFormatException e) {
          return UnparsedExpression.of(columnDefaultValue);
        }

      case BigQueryTypeConverter.NUMERIC:
        try {
          Integer precision = type.getColumnSize();
          Integer scale = type.getScale();
          if (precision != null && scale != null) {
            return Literals.decimalLiteral(Decimal.of(trimmedValue, precision, scale));
          } else {
            return Literals.decimalLiteral(Decimal.of(trimmedValue));
          }
        } catch (Exception e) {
          return UnparsedExpression.of(columnDefaultValue);
        }

      case BigQueryTypeConverter.BOOL:
        // Boolean already handled above, but keep for completeness
        Boolean boolVal = parseBooleanValue(trimmedValue);
        return boolVal != null
            ? Literals.booleanLiteral(boolVal)
            : UnparsedExpression.of(columnDefaultValue);

      case BigQueryTypeConverter.STRING:
        return Literals.stringLiteral(trimmedValue);

      default:
        // For other types (including BIGNUMERIC, GEOGRAPHY, JSON, STRUCT, RANGE),
        // return as unparsed expression
        return UnparsedExpression.of(columnDefaultValue);
    }
  }

  /**
   * Parses a boolean value from a string.
   *
   * @param value the string value to parse
   * @return Boolean.TRUE, Boolean.FALSE, or null if not a boolean
   */
  private Boolean parseBooleanValue(String value) {
    if (value.equalsIgnoreCase("true")) {
      return Boolean.TRUE;
    } else if (value.equalsIgnoreCase("false")) {
      return Boolean.FALSE;
    }
    return null;
  }
}
