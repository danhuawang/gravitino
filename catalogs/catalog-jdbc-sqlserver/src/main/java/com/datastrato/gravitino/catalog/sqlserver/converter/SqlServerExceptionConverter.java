/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import java.sql.SQLException;
import java.util.Locale;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.exceptions.UnauthorizedException;

/**
 * Exception converter for SQL Server catalog. Maps SQL Server error codes to Gravitino exceptions.
 */
public class SqlServerExceptionConverter extends JdbcExceptionConverter {

  /** Object already exists (table or view). Schema duplicates use error code 15032. */
  private static final int ERROR_OBJECT_ALREADY_EXISTS = 2714;

  /** Schema already exists. */
  private static final int ERROR_SCHEMA_ALREADY_EXISTS = 15032;

  /** Invalid object name (table not found). */
  private static final int ERROR_INVALID_OBJECT_NAME = 208;

  /** Cannot drop, object does not exist. */
  private static final int ERROR_CANNOT_DROP_NOT_EXIST = 3701;

  /** Cannot find schema. */
  private static final int ERROR_CANNOT_FIND_SCHEMA = 15151;

  /** Permission denied on object. */
  private static final int ERROR_PERMISSION_DENIED = 229;

  /** Login failed. */
  private static final int ERROR_LOGIN_FAILED = 18456;

  /** Cannot open database. */
  private static final int ERROR_CANNOT_OPEN_DATABASE = 4060;

  @SuppressWarnings("FormatStringAnnotation")
  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException se) {
    int errorCode = se.getErrorCode();
    switch (errorCode) {
      case ERROR_OBJECT_ALREADY_EXISTS:
        return new TableAlreadyExistsException(se, se.getMessage());

      case ERROR_SCHEMA_ALREADY_EXISTS:
        return new SchemaAlreadyExistsException(se, se.getMessage());

      case ERROR_INVALID_OBJECT_NAME:
        return new NoSuchTableException(se, se.getMessage());

      case ERROR_CANNOT_DROP_NOT_EXIST:
        // 3701 message format: "Cannot drop the <type> '...', because it does not exist..."
        // where <type> is "schema", "table", "view", etc.
        if (se.getMessage() != null
            && se.getMessage().toLowerCase(Locale.ROOT).contains("the schema")) {
          return new NoSuchSchemaException(se, se.getMessage());
        }
        return new NoSuchTableException(se, se.getMessage());

      case ERROR_CANNOT_FIND_SCHEMA:
        return new NoSuchSchemaException(se, se.getMessage());

      case ERROR_PERMISSION_DENIED:
        return new ForbiddenException(se, se.getMessage());

      case ERROR_LOGIN_FAILED:
        return new UnauthorizedException(se, se.getMessage());

      case ERROR_CANNOT_OPEN_DATABASE:
        return new NoSuchSchemaException(se, se.getMessage());

      default:
        return new GravitinoRuntimeException(se, se.getMessage());
    }
  }
}
