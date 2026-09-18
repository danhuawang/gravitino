/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.relational.utils;

import com.datastrato.gravitino.scim.storage.relational.converters.ScimSQLExceptionConverterFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;

/** Utilities for translating JDBC exceptions in SCIM storage. */
public final class ScimExceptionUtils {

  private ScimExceptionUtils() {}

  /**
   * Converts JDBC SQL exceptions into SCIM exceptions when possible.
   *
   * @param re the runtime exception thrown by JDBC/MyBatis
   * @param resourceType the resource type, for example {@code token}
   * @param name the resource name
   * @throws IOException if the SQL exception cannot be mapped to a SCIM exception
   */
  public static void checkSQLException(RuntimeException re, String resourceType, String name)
      throws IOException {
    if (re.getCause() instanceof SQLException) {
      ScimSQLExceptionConverterFactory.getConverter()
          .toScimException((SQLException) re.getCause(), resourceType, name);
    }
  }

  /**
   * Returns whether the runtime exception was caused by a unique constraint violation.
   *
   * @param re the runtime exception thrown by JDBC/MyBatis
   * @return true when the cause is a duplicate entry SQL exception
   */
  public static boolean isDuplicateEntry(RuntimeException re) {
    if (!(re.getCause() instanceof SQLException)) {
      return false;
    }
    return ScimSQLExceptionConverterFactory.getConverter()
        .isDuplicateEntry((SQLException) re.getCause());
  }

  /**
   * Same as {@link #checkSQLException(RuntimeException, String, String)}, but wraps an unmapped
   * {@link IOException} so callers that do not declare {@code IOException} can still use it.
   *
   * <p>A mapped {@link IllegalArgumentException} (value too long) propagates unchanged.
   *
   * @param re the runtime exception thrown by JDBC/MyBatis
   * @param resourceType the resource type, for example {@code user}
   * @param name the resource name
   */
  public static void checkSQLExceptionUnchecked(
      RuntimeException re, String resourceType, String name) {
    try {
      checkSQLException(re, resourceType, name);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
