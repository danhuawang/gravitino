/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import java.math.BigDecimal;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;

/** Column default value converter for Oracle. */
public class OracleColumnDefaultValueConverter extends JdbcColumnDefaultValueConverter {

  @Override
  public Expression toGravitino(
      JdbcTypeConverter.JdbcTypeBean typeBean,
      String columnDefaultValue,
      boolean isExpression,
      boolean nullable) {
    if (columnDefaultValue == null) {
      return DEFAULT_VALUE_NOT_SET;
    }

    String defaultVal = columnDefaultValue.trim();
    if (StringUtils.isEmpty(defaultVal) || "NULL".equalsIgnoreCase(defaultVal)) {
      return nullable ? Literals.NULL : DEFAULT_VALUE_NOT_SET;
    }
    if ("SYSDATE".equalsIgnoreCase(defaultVal)) {
      return FunctionExpression.of("SYSDATE");
    }
    if ("SYSTIMESTAMP".equalsIgnoreCase(defaultVal)) {
      return FunctionExpression.of("SYSTIMESTAMP");
    }
    if ("CURRENT_TIMESTAMP".equalsIgnoreCase(defaultVal)) {
      return FunctionExpression.of("CURRENT_TIMESTAMP");
    }
    if ("CURRENT_DATE".equalsIgnoreCase(defaultVal)) {
      return FunctionExpression.of("CURRENT_DATE");
    }
    if (defaultVal.toUpperCase(Locale.ROOT).startsWith("SYS_GUID")) {
      return FunctionExpression.of("SYS_GUID");
    }

    if (defaultVal.startsWith("'") && defaultVal.endsWith("'")) {
      String content = unescapeSqlStringLiteral(defaultVal);
      if (content.isEmpty()) {
        return nullable ? Literals.NULL : DEFAULT_VALUE_NOT_SET;
      }
      return Literals.stringLiteral(content);
    }

    String typeName = typeBean.getTypeName().toUpperCase(Locale.ROOT);
    if ("NUMBER".equals(typeName)
        || "BINARY_FLOAT".equals(typeName)
        || "BINARY_DOUBLE".equals(typeName)
        || "FLOAT".equals(typeName)) {
      try {
        if (typeBean.getScale() != null && typeBean.getScale() > 0) {
          BigDecimal decimalValue = new BigDecimal(defaultVal);
          return Literals.decimalLiteral(
              Decimal.of(
                  decimalValue.toPlainString(),
                  decimalValue.precision(),
                  Math.max(decimalValue.scale(), 0)));
        }
        return Literals.longLiteral(Long.parseLong(defaultVal));
      } catch (NumberFormatException e) {
        // fall through
      }
    }

    return UnparsedExpression.of(defaultVal);
  }

  @Override
  public String fromGravitino(Expression defaultValue) {
    if (defaultValue == DEFAULT_VALUE_NOT_SET) {
      return null;
    }
    if (defaultValue == Literals.NULL) {
      return "NULL";
    }
    if (defaultValue instanceof FunctionExpression) {
      String functionName =
          ((FunctionExpression) defaultValue).functionName().toUpperCase(Locale.ROOT);
      switch (functionName) {
        case "SYSDATE":
          return "SYSDATE";
        case "SYSTIMESTAMP":
          return "SYSTIMESTAMP";
        case "CURRENT_TIMESTAMP":
          return "CURRENT_TIMESTAMP";
        case "CURRENT_DATE":
          return "CURRENT_DATE";
        case "SYS_GUID":
          return "SYS_GUID()";
        default:
          return functionName + "()";
      }
    }
    if (defaultValue instanceof Literal && ((Literal<?>) defaultValue).value() instanceof String) {
      return escapeSqlStringLiteral((String) ((Literal<?>) defaultValue).value());
    }
    if (defaultValue instanceof UnparsedExpression) {
      return ((UnparsedExpression) defaultValue).unparsedExpression();
    }
    return super.fromGravitino(defaultValue);
  }

  private static String escapeSqlStringLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String unescapeSqlStringLiteral(String quoted) {
    return quoted.substring(1, quoted.length() - 1).replace("''", "'");
  }
}
