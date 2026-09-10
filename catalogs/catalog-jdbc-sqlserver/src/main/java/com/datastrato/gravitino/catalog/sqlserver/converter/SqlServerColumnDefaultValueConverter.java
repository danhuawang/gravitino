/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import com.datastrato.gravitino.catalog.sqlserver.utils.SqlServerUtils;
import java.util.Locale;
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
      return DEFAULT_VALUE_NOT_SET;
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

    // Check for NULL — SQL Server may store as (NULL) or ('NULL') depending on how
    // the DEFAULT constraint was created. For string types (char, nchar, varchar, nvarchar),
    // ('NULL') is a valid string literal default, so only treat unquoted NULL as Literals.NULL.
    if (stripped.equalsIgnoreCase(NULL)) {
      return Literals.NULL;
    }
    String typeName = type.getTypeName().toLowerCase(Locale.ROOT);
    if (stripped.equalsIgnoreCase("'NULL'") && !isStringType(typeName)) {
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
      String prefix =
          (literal.dataType() instanceof Types.StringType
                  || literal.dataType() instanceof Types.VarCharType
                  || literal.dataType() instanceof Types.FixedCharType)
              ? "N"
              : "";
      return String.format(
          "%s'%s'", prefix, SqlServerUtils.escapeQuote(literal.value().toString()));
    }

    throw new IllegalArgumentException("Not a supported column default value: " + defaultValue);
  }

  private Expression parseLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    String typeName = type.getTypeName().toLowerCase(Locale.ROOT);
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
      case "numeric":
        return Literals.decimalLiteral(Decimal.of(value, type.getColumnSize(), type.getScale()));
      case SqlServerTypeConverter.BIT:
        // SQL Server stores bit defaults as 0 or 1
        return Literals.booleanLiteral("1".equals(value));
      case SqlServerTypeConverter.CHAR:
      case "nchar":
      case JdbcTypeConverter.VARCHAR:
      case SqlServerTypeConverter.NVARCHAR:
        // Strip surrounding single quotes if present
        String strVal = value;
        if (strVal.startsWith("N'") || strVal.startsWith("n'")) {
          strVal = strVal.substring(1);
        }
        if (strVal.startsWith("'") && strVal.endsWith("'")) {
          strVal = strVal.substring(1, strVal.length() - 1);
        }
        // Unescape SQL Server doubled single quotes: '' -> '
        strVal = strVal.replace("''", "'");
        if (type.getColumnSize() != null && type.getColumnSize() != Integer.MAX_VALUE) {
          if (SqlServerTypeConverter.CHAR.equals(typeName) || "nchar".equals(typeName)) {
            return Literals.of(strVal, Types.FixedCharType.of(type.getColumnSize()));
          }
          return Literals.varcharLiteral(type.getColumnSize(), strVal);
        }
        return Literals.stringLiteral(strVal);
      default:
        throw new IllegalArgumentException("Unknown data type for literal: " + typeName);
    }
  }

  private static boolean isStringType(String typeName) {
    return SqlServerTypeConverter.CHAR.equals(typeName)
        || "nchar".equals(typeName)
        || JdbcTypeConverter.VARCHAR.equals(typeName)
        || SqlServerTypeConverter.NVARCHAR.equals(typeName);
  }
}
