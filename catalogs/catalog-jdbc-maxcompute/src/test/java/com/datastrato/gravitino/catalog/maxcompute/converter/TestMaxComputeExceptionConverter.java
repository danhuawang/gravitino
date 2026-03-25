/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute.converter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for MaxComputeExceptionConverter. */
class TestMaxComputeExceptionConverter {

  private MaxComputeExceptionConverter converter;

  @BeforeEach
  void setUp() {
    converter = new MaxComputeExceptionConverter();
  }

  @Test
  void testIsAuthenticationFailure() {
    assertTrue(MaxComputeExceptionConverter.isAuthenticationFailure("Access denied for user"));
    assertTrue(
        MaxComputeExceptionConverter.isAuthenticationFailure("authentication failed for account"));
    assertTrue(MaxComputeExceptionConverter.isAuthenticationFailure("Invalid AccessKeyId"));
    assertTrue(MaxComputeExceptionConverter.isAuthenticationFailure("invalid access key provided"));
    assertTrue(MaxComputeExceptionConverter.isAuthenticationFailure("Signature not match"));
    assertTrue(
        MaxComputeExceptionConverter.isAuthenticationFailure("signature does not match expected"));
    assertTrue(MaxComputeExceptionConverter.isAuthenticationFailure("InvalidAccessKeyId error"));
    assertTrue(MaxComputeExceptionConverter.isAuthenticationFailure("SignatureDoesNotMatch"));

    assertFalse(MaxComputeExceptionConverter.isAuthenticationFailure(null));
    assertFalse(MaxComputeExceptionConverter.isAuthenticationFailure(""));
    assertFalse(MaxComputeExceptionConverter.isAuthenticationFailure("   "));
    assertFalse(MaxComputeExceptionConverter.isAuthenticationFailure("Some other error"));
  }

  @Test
  void testIsPermissionDenied() {
    assertTrue(MaxComputeExceptionConverter.isPermissionDenied("Access Denied - no permission"));
    assertTrue(MaxComputeExceptionConverter.isPermissionDenied("You have no privilege to access"));
    assertTrue(MaxComputeExceptionConverter.isPermissionDenied("permission denied for operation"));
    assertTrue(MaxComputeExceptionConverter.isPermissionDenied("User is not authorized"));
    assertTrue(MaxComputeExceptionConverter.isPermissionDenied("Authorization exception occurred"));
    assertTrue(MaxComputeExceptionConverter.isPermissionDenied("You are unauthorized to access"));

    // Test ODPS error codes
    assertTrue(
        MaxComputeExceptionConverter.isPermissionDenied(
            "ODPS-0110011: Authorization exception - You are unauthorized"));
    assertTrue(
        MaxComputeExceptionConverter.isPermissionDenied(
            "ODPS-0030001: Authorization exception - permission denied"));
    assertTrue(
        MaxComputeExceptionConverter.isPermissionDenied(
            "ODPS-0420095: Access Denied - You have no privilege"));

    assertFalse(MaxComputeExceptionConverter.isPermissionDenied(null));
    assertFalse(MaxComputeExceptionConverter.isPermissionDenied(""));
    assertFalse(MaxComputeExceptionConverter.isPermissionDenied("   "));
    assertFalse(MaxComputeExceptionConverter.isPermissionDenied("Some other error"));
  }

  @Test
  void testIsSchemaNotFound() {
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("project not found: test_project"));
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("Project not found in region"));
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("schema not found: test_schema"));
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("Schema not found error"));
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("Unknown project: test"));
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("unknown project specified"));
    assertTrue(MaxComputeExceptionConverter.isSchemaNotFound("The project does not exist"));

    assertFalse(MaxComputeExceptionConverter.isSchemaNotFound(null));
    assertFalse(MaxComputeExceptionConverter.isSchemaNotFound(""));
    assertFalse(MaxComputeExceptionConverter.isSchemaNotFound("   "));
    assertFalse(MaxComputeExceptionConverter.isSchemaNotFound("Some other error"));
  }

  @Test
  void testIsTableNotFound() {
    assertTrue(MaxComputeExceptionConverter.isTableNotFound("table not found: test_table"));
    assertTrue(MaxComputeExceptionConverter.isTableNotFound("Table not found in schema"));
    assertTrue(MaxComputeExceptionConverter.isTableNotFound("Unknown table: test"));
    assertTrue(MaxComputeExceptionConverter.isTableNotFound("unknown table specified"));

    assertFalse(MaxComputeExceptionConverter.isTableNotFound(null));
    assertFalse(MaxComputeExceptionConverter.isTableNotFound(""));
    assertFalse(MaxComputeExceptionConverter.isTableNotFound("   "));
    assertFalse(MaxComputeExceptionConverter.isTableNotFound("Some other error"));
  }

  @Test
  void testIsConnectionFailure() {
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Connection refused by server"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Connection timed out"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Unable to connect to host"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Network is unreachable"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("No route to host"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Connection reset by peer"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Socket timeout occurred"));
    assertTrue(MaxComputeExceptionConverter.isConnectionFailure("Read timed out"));

    assertFalse(MaxComputeExceptionConverter.isConnectionFailure(null));
    assertFalse(MaxComputeExceptionConverter.isConnectionFailure(""));
    assertFalse(MaxComputeExceptionConverter.isConnectionFailure("   "));
    assertFalse(MaxComputeExceptionConverter.isConnectionFailure("Some other error"));
  }

  @Test
  void testToGravitinoExceptionAuthenticationFailure() {
    SQLException se = new SQLException("Access denied for user 'test'");
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(UnauthorizedException.class, result);
  }

  @Test
  void testToGravitinoExceptionPermissionDenied() {
    SQLException se =
        new SQLException("ODPS-0110011: Authorization exception - You are unauthorized");
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(UnauthorizedException.class, result);
  }

  @Test
  void testToGravitinoExceptionSchemaNotFound() {
    SQLException se = new SQLException("Project not found: test_project");
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(NoSuchSchemaException.class, result);
  }

  @Test
  void testToGravitinoExceptionTableNotFound() {
    SQLException se = new SQLException("Table not found: test_table");
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(NoSuchTableException.class, result);
  }

  @Test
  void testToGravitinoExceptionConnectionFailure() {
    SQLException se = new SQLException("Connection refused by server");
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(ConnectionFailedException.class, result);
  }

  @Test
  void testToGravitinoExceptionGenericError() {
    SQLException se = new SQLException("Some generic error occurred");
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(GravitinoRuntimeException.class, result);
    assertFalse(result instanceof ConnectionFailedException);
    assertFalse(result instanceof UnauthorizedException);
    assertFalse(result instanceof NoSuchSchemaException);
    assertFalse(result instanceof NoSuchTableException);
  }

  @Test
  void testToGravitinoExceptionNullMessage() {
    SQLException se = new SQLException((String) null);
    GravitinoRuntimeException result = converter.toGravitinoException(se);
    assertInstanceOf(GravitinoRuntimeException.class, result);
  }

  @Test
  void testIsSchemaAlreadyExists() {
    // Positive cases
    assertTrue(
        MaxComputeExceptionConverter.isSchemaAlreadyExists("Schema test_schema already exists"));
    assertTrue(
        MaxComputeExceptionConverter.isSchemaAlreadyExists("schema already exists in project"));
    assertTrue(
        MaxComputeExceptionConverter.isSchemaAlreadyExists("The schema 'test' already exists"));

    // Negative cases - null/empty
    assertFalse(MaxComputeExceptionConverter.isSchemaAlreadyExists(null));
    assertFalse(MaxComputeExceptionConverter.isSchemaAlreadyExists(""));
    assertFalse(MaxComputeExceptionConverter.isSchemaAlreadyExists("   "));

    // Negative cases - missing keywords
    assertFalse(MaxComputeExceptionConverter.isSchemaAlreadyExists("schema not found"));
    assertFalse(MaxComputeExceptionConverter.isSchemaAlreadyExists("already exists"));

    // Negative cases - table error should not match
    // When "table" appears before "schema", it's a table error, not a schema error
    assertFalse(
        MaxComputeExceptionConverter.isSchemaAlreadyExists(
            "table schema.tablename already exists"));
    assertFalse(
        MaxComputeExceptionConverter.isSchemaAlreadyExists("Table in schema already exists"));
  }

  @Test
  void testIsTableAlreadyExists() {
    // Positive cases
    assertTrue(
        MaxComputeExceptionConverter.isTableAlreadyExists("Table test_table already exists"));
    assertTrue(MaxComputeExceptionConverter.isTableAlreadyExists("table already exists in schema"));
    assertTrue(
        MaxComputeExceptionConverter.isTableAlreadyExists("The table 'test' already exists"));

    // Negative cases
    assertFalse(MaxComputeExceptionConverter.isTableAlreadyExists(null));
    assertFalse(MaxComputeExceptionConverter.isTableAlreadyExists(""));
    assertFalse(MaxComputeExceptionConverter.isTableAlreadyExists("   "));
    assertFalse(MaxComputeExceptionConverter.isTableAlreadyExists("table not found"));
    assertFalse(MaxComputeExceptionConverter.isTableAlreadyExists("already exists"));
  }
}
