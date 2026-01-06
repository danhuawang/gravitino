/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.bigquery.converter;

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
      case BigQueryTypeConverter.BIGNUMERIC:
        try {
          Integer precision = type.getColumnSize();
          Integer scale = type.getScale();
          // Gravitino Decimal supports precision up to 38, but BigQuery BIGNUMERIC supports up to
          // 76.76
          // For BIGNUMERIC with precision > 38, we cap it at 38 for Gravitino compatibility
          if (precision != null && precision > 38) {
            precision = 38;
            // Adjust scale proportionally if needed
            if (scale != null && scale > 38) {
              scale = 38;
            }
          }
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
        // For other types, return as unparsed expression
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
