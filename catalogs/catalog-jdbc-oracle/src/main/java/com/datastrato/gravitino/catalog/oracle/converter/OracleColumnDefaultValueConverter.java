/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
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
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Column default value converter for Oracle. */
public class OracleColumnDefaultValueConverter extends JdbcColumnDefaultValueConverter {

  private static final DateTimeFormatter ORACLE_TIMESTAMP_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd HH:mm:ss")
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .toFormatter();

  private boolean nativeBooleanSupported;

  /**
   * Configures whether boolean literals should use Oracle's native SQL boolean syntax.
   *
   * @param nativeBooleanSupported whether native SQL booleans are supported
   */
  public void setNativeBooleanSupported(boolean nativeBooleanSupported) {
    this.nativeBooleanSupported = nativeBooleanSupported;
  }

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
    if (OracleTypeConverter.BOOLEAN.equals(typeName)) {
      if ("TRUE".equalsIgnoreCase(defaultVal)) {
        return Literals.booleanLiteral(true);
      }
      if ("FALSE".equalsIgnoreCase(defaultVal)) {
        return Literals.booleanLiteral(false);
      }
    }
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
    if (defaultValue instanceof Literal) {
      Literal<?> literal = (Literal<?>) defaultValue;
      Type type = literal.dataType();
      if (type instanceof Types.BooleanType) {
        return formatBoolean(literal.value());
      }
      if (type instanceof Types.TimestampType) {
        return "TIMESTAMP "
            + escapeSqlStringLiteral(formatTimestamp(literal.value(), (Types.TimestampType) type));
      }
      if (type instanceof Types.DateType) {
        return "DATE " + escapeSqlStringLiteral(literal.value().toString());
      }
      if (literal.value() instanceof String) {
        return escapeSqlStringLiteral((String) literal.value());
      }
    }
    if (defaultValue instanceof UnparsedExpression) {
      return ((UnparsedExpression) defaultValue).unparsedExpression();
    }
    return super.fromGravitino(defaultValue);
  }

  private static String escapeSqlStringLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private String formatBoolean(Object value) {
    if (value instanceof Boolean) {
      if (nativeBooleanSupported) {
        return (Boolean) value ? "TRUE" : "FALSE";
      }
      return (Boolean) value ? "1" : "0";
    }
    String stringValue = value.toString();
    if ("true".equalsIgnoreCase(stringValue)) {
      return nativeBooleanSupported ? "TRUE" : "1";
    }
    if ("false".equalsIgnoreCase(stringValue)) {
      return nativeBooleanSupported ? "FALSE" : "0";
    }
    try {
      return new BigDecimal(stringValue).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid Oracle NUMBER(1) value: " + stringValue, e);
    }
  }

  private static String formatTimestamp(Object value, Types.TimestampType type) {
    if (type.hasTimeZone()) {
      OffsetDateTime timestamp =
          value instanceof OffsetDateTime
              ? (OffsetDateTime) value
              : OffsetDateTime.parse(value.toString());
      String offset = timestamp.getOffset().getId();
      return ORACLE_TIMESTAMP_FORMATTER.format(timestamp)
          + " "
          + ("Z".equals(offset) ? "+00:00" : offset);
    }
    LocalDateTime timestamp =
        value instanceof LocalDateTime
            ? (LocalDateTime) value
            : LocalDateTime.parse(value.toString());
    return ORACLE_TIMESTAMP_FORMATTER.format(timestamp);
  }

  private static String unescapeSqlStringLiteral(String quoted) {
    return quoted.substring(1, quoted.length() - 1).replace("''", "'");
  }
}
