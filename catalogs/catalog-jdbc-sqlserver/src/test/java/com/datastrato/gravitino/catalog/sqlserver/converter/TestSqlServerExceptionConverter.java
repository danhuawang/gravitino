/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import java.sql.SQLException;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for SqlServerExceptionConverter. */
public class TestSqlServerExceptionConverter {

  private final SqlServerExceptionConverter converter = new SqlServerExceptionConverter();

  @Test
  void testError2714ObjectAlreadyExists() {
    SQLException se =
        new SQLException("There is already an object named 'test_table'.", "S0001", 2714);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(TableAlreadyExistsException.class, result);
  }

  @Test
  void testError15032SchemaAlreadyExists() {
    SQLException se = new SQLException("The schema 'test' already exists.", "S0001", 15032);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(SchemaAlreadyExistsException.class, result);
  }

  @Test
  void testError208InvalidObjectName() {
    SQLException se = new SQLException("Invalid object name 'test_table'.", "S0001", 208);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(NoSuchTableException.class, result);
  }

  @Test
  void testError3701CannotDropSchemaNotExist() {
    SQLException se =
        new SQLException(
            "Cannot drop the schema 'test', because it does not exist.", "S0001", 3701);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(NoSuchSchemaException.class, result);
  }

  @Test
  void testError3701CannotDropTableNotExist() {
    SQLException se =
        new SQLException(
            "Cannot drop the table 'test_table', because it does not exist.", "S0001", 3701);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(NoSuchTableException.class, result);
  }

  @Test
  void testError15151CannotFindSchema() {
    SQLException se = new SQLException("Cannot find the schema 'test'.", "S0001", 15151);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(NoSuchSchemaException.class, result);
  }

  @Test
  void testError229PermissionDenied() {
    SQLException se = new SQLException("The SELECT permission was denied.", "S0001", 229);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(ForbiddenException.class, result);
  }

  @Test
  void testError18456LoginFailed() {
    SQLException se = new SQLException("Login failed for user 'sa'.", "S0001", 18456);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(UnauthorizedException.class, result);
  }

  @Test
  void testError4060CannotOpenDatabase() {
    SQLException se = new SQLException("Cannot open database 'nonexistent'.", "S0001", 4060);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(NoSuchSchemaException.class, result);
  }

  @Test
  void testUnmappedErrorCode() {
    SQLException se = new SQLException("Some unknown error.", "S0001", 99999);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(GravitinoRuntimeException.class, result);
    // Should NOT be a subclass like SchemaAlreadyExistsException
    Assertions.assertEquals(GravitinoRuntimeException.class, result.getClass());
  }
}
