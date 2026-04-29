/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import com.datastrato.gravitino.catalog.sqlserver.utils.SqlServerUtils;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Column default value converter for SQL Server catalog. */
public class SqlServerColumnDefaultValueConverter extends JdbcColumnDefaultValueConverter {

  private static final String GETDATE = "getdate()";

  @Override
  public Expression toGravitino(
      JdbcTypeConverter.JdbcTypeBean type,
      String columnDefaultValue,
      boolean isExpression,
      boolean nullable) {
    if (columnDefaultValue == null) {
      return nullable ? Literals.NULL : DEFAULT_VALUE_NOT_SET;
    }

    // SQL Server generated/computed columns should be returned as unparsed expressions
    if (isExpression) {
      return UnparsedExpression.of(columnDefaultValue);
    }

    // Strip outer parentheses that SQL Server wraps around default values
    String stripped = SqlServerUtils.stripOuterParentheses(columnDefaultValue);

    // Check for GETDATE() / CURRENT_TIMESTAMP
    if (stripped.equalsIgnoreCase(GETDATE) || stripped.equalsIgnoreCase(CURRENT_TIMESTAMP)) {
      return DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;
    }

    // Check for NULL
    if (stripped.equalsIgnoreCase(NULL)) {
      return Literals.NULL;
    }

    try {
      return parseLiteral(type, stripped);
    } catch (Exception e) {
      return UnparsedExpression.of(columnDefaultValue);
    }
  }

  @Override
  public String fromGravitino(Expression defaultValue) {
    if (DEFAULT_VALUE_NOT_SET.equals(defaultValue)) {
      return null;
    }

    if (defaultValue instanceof FunctionExpression) {
      FunctionExpression funcExpr = (FunctionExpression) defaultValue;
      if (funcExpr.functionName().equalsIgnoreCase(CURRENT_TIMESTAMP)) {
        return "GETDATE()";
      }
      return String.format("(%s)", funcExpr);
    }

    if (defaultValue instanceof Literal) {
      Literal<?> literal = (Literal<?>) defaultValue;
      if (defaultValue.equals(Literals.NULL)) {
        return NULL;
      }
      // SQL Server bit type uses 1/0 instead of true/false
      if (literal.dataType() instanceof Types.BooleanType) {
        return Boolean.parseBoolean(literal.value().toString()) ? "1" : "0";
      }
      if (literal.dataType() instanceof Type.NumericType) {
        return literal.value().toString();
      }
      return String.format("'%s'", SqlServerUtils.escapeQuote(literal.value().toString()));
    }

    throw new IllegalArgumentException("Not a supported column default value: " + defaultValue);
  }

  private Expression parseLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    String typeName = type.getTypeName().toLowerCase();
    switch (typeName) {
      case SqlServerTypeConverter.TINYINT:
        return Literals.byteLiteral(Byte.valueOf(value));
      case SqlServerTypeConverter.SMALLINT:
        return Literals.shortLiteral(Short.valueOf(value));
      case SqlServerTypeConverter.INT:
        return Literals.integerLiteral(Integer.valueOf(value));
      case SqlServerTypeConverter.BIGINT:
        return Literals.longLiteral(Long.valueOf(value));
      case SqlServerTypeConverter.REAL:
        return Literals.floatLiteral(Float.valueOf(value));
      case SqlServerTypeConverter.FLOAT:
        return Literals.doubleLiteral(Double.valueOf(value));
      case SqlServerTypeConverter.DECIMAL:
        return Literals.decimalLiteral(Decimal.of(value, type.getColumnSize(), type.getScale()));
      case SqlServerTypeConverter.BIT:
        // SQL Server stores bit defaults as 0 or 1
        return Literals.booleanLiteral("1".equals(value));
      case SqlServerTypeConverter.CHAR:
      case JdbcTypeConverter.VARCHAR:
      case SqlServerTypeConverter.NVARCHAR:
        // Strip surrounding single quotes if present
        String strVal = value;
        if (strVal.startsWith("'") && strVal.endsWith("'")) {
          strVal = strVal.substring(1, strVal.length() - 1);
        }
        // Unescape SQL Server doubled single quotes: '' -> '
        strVal = strVal.replace("''", "'");
        if (JdbcTypeConverter.VARCHAR.equals(typeName)) {
          return Literals.varcharLiteral(type.getColumnSize(), strVal);
        }
        if (SqlServerTypeConverter.CHAR.equals(typeName)) {
          return Literals.of(strVal, Types.FixedCharType.of(type.getColumnSize()));
        }
        return Literals.stringLiteral(strVal);
      default:
        throw new IllegalArgumentException("Unknown data type for literal: " + typeName);
    }
  }
}
