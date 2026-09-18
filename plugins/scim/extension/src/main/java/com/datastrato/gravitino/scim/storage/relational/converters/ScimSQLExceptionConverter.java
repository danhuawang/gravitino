/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.relational.converters;

import java.io.IOException;
import java.sql.SQLException;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converts JDBC SQL exceptions to SCIM storage exceptions. */
public final class ScimSQLExceptionConverter {
  private static final Logger LOG = LoggerFactory.getLogger(ScimSQLExceptionConverter.class);

  /** MySQL duplicate entry error code. */
  private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;

  /** H2 duplicate entry error code. */
  private static final int H2_DUPLICATE_ENTRY_ERROR_CODE = 23505;

  /** PostgreSQL duplicate entry SQL state. */
  private static final String POSTGRESQL_DUPLICATE_ENTRY_SQL_STATE = "23505";

  /** MySQL value-too-long error code. */
  private static final int MYSQL_VALUE_TOO_LONG_ERROR_CODE = 1406;

  /** H2 value-too-long error code. */
  private static final int H2_VALUE_TOO_LONG_ERROR_CODE = 22001;

  /** PostgreSQL and H2 value-too-long SQL state. */
  private static final String VALUE_TOO_LONG_SQL_STATE = "22001";

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
    if (isValueTooLong(sqlException)) {
      // Log the SQL exception but do not attach it: SCIM error responses serialize the cause.
      LOG.warn(
          "Rejected a SCIM {} value that exceeds the maximum length of its column",
          resourceType,
          sqlException);
      throw new IllegalArgumentException(valueTooLongMessage(resourceType));
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

  /**
   * Returns whether the SQL exception reports a value that is too long for its column.
   *
   * @param sqlException the SQL exception to inspect
   * @return true when the exception is a value-too-long error
   */
  public boolean isValueTooLong(SQLException sqlException) {
    switch (jdbcType) {
      case MYSQL:
        return sqlException.getErrorCode() == MYSQL_VALUE_TOO_LONG_ERROR_CODE;
      case H2:
        return sqlException.getErrorCode() == H2_VALUE_TOO_LONG_ERROR_CODE
            || VALUE_TOO_LONG_SQL_STATE.equals(sqlException.getSQLState());
      case POSTGRESQL:
        return VALUE_TOO_LONG_SQL_STATE.equals(sqlException.getSQLState());
      default:
        throw new IllegalStateException("Unsupported JDBC type: " + jdbcType);
    }
  }

  static String valueTooLongMessage(String resourceType) {
    return String.format(
        "The SCIM %s has a value that exceeds the maximum length of its column.", resourceType);
  }

  private IOException toIOException(SQLException sqlException) {
    String sqlMessage = sqlException.getMessage();
    if (jdbcType == JdbcType.H2) {
      String detail =
          sqlMessage == null || sqlMessage.isBlank()
              ? "error code: " + sqlException.getErrorCode()
              : "error code: " + sqlException.getErrorCode() + ", " + sqlMessage;
      return new IOException(detail, sqlException);
    }
    return new IOException(sqlException);
  }
}
