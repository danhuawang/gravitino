/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational.converters;

import java.io.IOException;
import java.sql.SQLException;
import org.apache.gravitino.exceptions.AlreadyExistsException;

/** Converts JDBC SQL exceptions to SCIM storage exceptions. */
public final class ScimSQLExceptionConverter {

  /** MySQL duplicate entry error code. */
  private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;

  /** H2 duplicate entry error code. */
  private static final int H2_DUPLICATE_ENTRY_ERROR_CODE = 23505;

  /** PostgreSQL duplicate entry SQL state. */
  private static final String POSTGRESQL_DUPLICATE_ENTRY_SQL_STATE = "23505";

  /** Supported JDBC backend types for SCIM relational storage. */
  public enum JdbcType {
    MYSQL,
    H2,
    POSTGRESQL
  }

  private final JdbcType jdbcType;

  /**
   * Creates a converter for the given JDBC backend type.
   *
   * @param jdbcType the JDBC backend type
   */
  public ScimSQLExceptionConverter(JdbcType jdbcType) {
    this.jdbcType = jdbcType;
  }

  /**
   * Converts a JDBC exception to a SCIM storage exception when possible.
   *
   * @param sqlException the SQL exception to map
   * @param resourceType the resource type, for example {@code token}
   * @param name the resource name
   * @throws IOException if the SQL exception cannot be mapped
   */
  @SuppressWarnings("FormatStringAnnotation")
  public void toScimException(SQLException sqlException, String resourceType, String name)
      throws IOException {
    if (isDuplicateEntry(sqlException)) {
      throw new AlreadyExistsException(
          sqlException, "SCIM %s %s already exists", resourceType, name);
    }
    throw toIOException(sqlException);
  }

  /**
   * Returns whether the SQL exception represents a unique constraint violation.
   *
   * @param sqlException the SQL exception to inspect
   * @return true when the exception is a duplicate entry error
   */
  public boolean isDuplicateEntry(SQLException sqlException) {
    switch (jdbcType) {
      case MYSQL:
        return sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY_ERROR_CODE;
      case H2:
        return sqlException.getErrorCode() == H2_DUPLICATE_ENTRY_ERROR_CODE
            || sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY_ERROR_CODE;
      case POSTGRESQL:
        return POSTGRESQL_DUPLICATE_ENTRY_SQL_STATE.equals(sqlException.getSQLState());
      default:
        throw new IllegalStateException("Unsupported JDBC type: " + jdbcType);
    }
  }

  private IOException toIOException(SQLException sqlException) {
    if (jdbcType == JdbcType.H2) {
      return new IOException("error code: " + sqlException.getErrorCode(), sqlException);
    }
    return new IOException(sqlException);
  }
}
