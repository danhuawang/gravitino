/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational.utils;

import com.datastrato.gravitino.scim.storage.relational.converters.ScimSQLExceptionConverterFactory;
import java.io.IOException;
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
}
