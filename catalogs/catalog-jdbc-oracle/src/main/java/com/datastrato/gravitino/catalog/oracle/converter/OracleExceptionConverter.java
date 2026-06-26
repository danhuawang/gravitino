/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exception converter for Oracle JDBC errors. Maps ORA- error codes to Gravitino exceptions. */
public class OracleExceptionConverter extends JdbcExceptionConverter {

  private static final Logger LOG = LoggerFactory.getLogger(OracleExceptionConverter.class);

  // ORA-01918: user 'xxx' does not exist
  static final int ERROR_CODE_USER_NOT_EXIST = 1918;

  // ORA-01435: user does not exist
  static final int ERROR_CODE_CURRENT_SCHEMA_USER_NOT_EXIST = 1435;

  // ORA-01920: user name 'xxx' conflicts with another user or role name
  static final int ERROR_CODE_USER_ALREADY_EXISTS = 1920;

  // ORA-00942: table or view does not exist
  static final int ERROR_CODE_TABLE_NOT_EXIST = 942;

  // ORA-00955: name is already used by an existing object
  static final int ERROR_CODE_NAME_ALREADY_USED = 955;

  // ORA-01031: insufficient privileges
  static final int ERROR_CODE_INSUFFICIENT_PRIVILEGES = 1031;

  @SuppressWarnings("FormatStringAnnotation")
  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException sqlException) {
    int errorCode = sqlException.getErrorCode();
    switch (errorCode) {
      case ERROR_CODE_USER_NOT_EXIST:
      case ERROR_CODE_CURRENT_SCHEMA_USER_NOT_EXIST:
        return new NoSuchSchemaException(sqlException, sqlException.getMessage());
      case ERROR_CODE_USER_ALREADY_EXISTS:
        return new SchemaAlreadyExistsException(sqlException, sqlException.getMessage());
      case ERROR_CODE_TABLE_NOT_EXIST:
        return new NoSuchTableException(sqlException, sqlException.getMessage());
      case ERROR_CODE_NAME_ALREADY_USED:
        return new TableAlreadyExistsException(sqlException, sqlException.getMessage());
      case ERROR_CODE_INSUFFICIENT_PRIVILEGES:
        return new GravitinoRuntimeException(
            sqlException, "Insufficient privileges: %s", sqlException.getMessage());
      default:
        LOG.warn(
            "Unrecognized Oracle error code {} (SQL state: {}): {}",
            errorCode,
            sqlException.getSQLState(),
            sqlException.getMessage());
        return new GravitinoRuntimeException(sqlException, sqlException.getMessage());
    }
  }
}
