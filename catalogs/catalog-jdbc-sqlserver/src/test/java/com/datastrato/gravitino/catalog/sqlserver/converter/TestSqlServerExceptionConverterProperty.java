/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property-style tests for SqlServerExceptionConverter. Validates that exception mapping dispatches
 * correctly by error code.
 */
public class TestSqlServerExceptionConverterProperty {

  private final SqlServerExceptionConverter converter = new SqlServerExceptionConverter();

  /** Provides mapped error codes with expected exception types. */
  static Stream<Arguments> mappedErrorCodes() {
    return Stream.of(
        Arguments.of(
            2714, "object named 'test_table' already exists", TableAlreadyExistsException.class),
        Arguments.of(15032, "schema exists", SchemaAlreadyExistsException.class),
        Arguments.of(208, "invalid object", NoSuchTableException.class),
        Arguments.of(3701, "Cannot drop the schema 'test'", NoSuchSchemaException.class),
        Arguments.of(3701, "Cannot drop the table 'test'", NoSuchTableException.class),
        Arguments.of(15151, "cannot find schema", NoSuchSchemaException.class),
        Arguments.of(229, "permission denied", ForbiddenException.class),
        Arguments.of(18456, "login failed", UnauthorizedException.class),
        Arguments.of(4060, "cannot open database", NoSuchSchemaException.class));
  }

  @ParameterizedTest
  @MethodSource("mappedErrorCodes")
  void testMappedErrorCodesProduceCorrectExceptionType(
      int errorCode, String message, Class<? extends GravitinoRuntimeException> expectedType) {
    SQLException se = new SQLException(message, "S0001", errorCode);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertInstanceOf(
        expectedType,
        result,
        String.format(
            "Error code %d with message '%s' should produce %s but got %s",
            errorCode, message, expectedType.getSimpleName(), result.getClass().getSimpleName()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 100, 999, 5000, 99999})
  void testUnmappedErrorCodesProduceGravitinoRuntimeException(int errorCode) {
    SQLException se = new SQLException("Unknown error", "S0001", errorCode);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertEquals(
        GravitinoRuntimeException.class,
        result.getClass(),
        "Unmapped error code " + errorCode + " should produce GravitinoRuntimeException");
  }

  @ParameterizedTest
  @MethodSource("mappedErrorCodes")
  void testExceptionPreservesOriginalMessage(
      int errorCode, String message, Class<? extends GravitinoRuntimeException> expectedType) {
    SQLException se = new SQLException(message, "S0001", errorCode);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    Assertions.assertTrue(
        result.getMessage().contains(message), "Exception message should contain original message");
  }
}
