/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.relational.converters;

import com.google.common.base.Preconditions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;

/** Factory for SCIM JDBC SQL exception converters. */
public final class ScimSQLExceptionConverterFactory {
  private static final Pattern TYPE_PATTERN = Pattern.compile("jdbc:(\\w+):");
  private static volatile ScimSQLExceptionConverter converter;

  private ScimSQLExceptionConverterFactory() {}

  /**
   * Initializes the SQL exception converter from the JDBC backend URL in config.
   *
   * @param config the server configuration
   */
  public static synchronized void initConverter(Config config) {
    if (converter == null) {
      String jdbcUrl = config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL);
      converter = new ScimSQLExceptionConverter(parseJdbcType(jdbcUrl));
    }
  }

  /**
   * Returns the initialized SQL exception converter.
   *
   * @return the SQL exception converter
   */
  public static ScimSQLExceptionConverter getConverter() {
    Preconditions.checkState(converter != null, "SCIM exception converter is not initialized.");
    return converter;
  }

  /** Closes and resets the SQL exception converter. */
  public static void close() {
    if (converter != null) {
      synchronized (ScimSQLExceptionConverterFactory.class) {
        if (converter != null) {
          converter = null;
        }
      }
    }
  }

  private static ScimSQLExceptionConverter.JdbcType parseJdbcType(String jdbcUrl) {
    Matcher typeMatcher = TYPE_PATTERN.matcher(jdbcUrl);
    if (!typeMatcher.find()) {
      throw new IllegalArgumentException(
          String.format("Cannot find jdbc type in jdbc url: %s", jdbcUrl));
    }

    String jdbcType = typeMatcher.group(1);
    if (jdbcType.equalsIgnoreCase("mysql")) {
      return ScimSQLExceptionConverter.JdbcType.MYSQL;
    } else if (jdbcType.equalsIgnoreCase("h2")) {
      return ScimSQLExceptionConverter.JdbcType.H2;
    } else if (jdbcType.equalsIgnoreCase("postgresql")) {
      return ScimSQLExceptionConverter.JdbcType.POSTGRESQL;
    }
    throw new IllegalArgumentException(String.format("Unsupported jdbc type: %s", jdbcType));
  }
}
