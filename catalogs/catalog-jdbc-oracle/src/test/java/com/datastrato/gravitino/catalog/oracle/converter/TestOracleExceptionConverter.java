/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import java.sql.SQLException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleExceptionConverter {

  private static final OracleExceptionConverter CONVERTER = new OracleExceptionConverter();

  @Test
  void testOra01918UserNotExist() {
    SQLException e = new SQLException("ORA-01918: user does not exist", null, 1918);
    Assertions.assertInstanceOf(NoSuchSchemaException.class, CONVERTER.toGravitinoException(e));
  }

  @Test
  void testOra01435CurrentSchemaUserNotExist() {
    SQLException e = new SQLException("ORA-01435: user does not exist", null, 1435);
    Assertions.assertInstanceOf(NoSuchSchemaException.class, CONVERTER.toGravitinoException(e));
  }

  @Test
  void testOra01920UserAlreadyExists() {
    SQLException e = new SQLException("ORA-01920: user name conflicts", null, 1920);
    Assertions.assertInstanceOf(
        SchemaAlreadyExistsException.class, CONVERTER.toGravitinoException(e));
  }

  @Test
  void testOra00942TableNotExist() {
    SQLException e = new SQLException("ORA-00942: table or view does not exist", null, 942);
    Assertions.assertInstanceOf(NoSuchTableException.class, CONVERTER.toGravitinoException(e));
  }

  @Test
  void testOra00955NameAlreadyUsed() {
    SQLException e = new SQLException("ORA-00955: name is already used", null, 955);
    Assertions.assertInstanceOf(
        TableAlreadyExistsException.class, CONVERTER.toGravitinoException(e));
  }

  @Test
  void testOra01031InsufficientPrivileges() {
    SQLException e = new SQLException("ORA-01031: insufficient privileges", null, 1031);
    GravitinoRuntimeException result = CONVERTER.toGravitinoException(e);
    Assertions.assertInstanceOf(GravitinoRuntimeException.class, result);
    Assertions.assertTrue(result.getMessage().contains("Insufficient privileges:"));
  }

  @Test
  void testUnrecognizedErrorCodeFallsToRuntimeException() {
    SQLException e = new SQLException("ORA-12345: some unknown error", null, 12345);
    GravitinoRuntimeException result = CONVERTER.toGravitinoException(e);
    Assertions.assertInstanceOf(GravitinoRuntimeException.class, result);
    Assertions.assertFalse(result instanceof NoSuchSchemaException);
    Assertions.assertFalse(result instanceof NoSuchTableException);
  }
}
