/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.maxcompute.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeExceptionConverter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for MaxComputeDatabaseOperations. */
class TestMaxComputeDatabaseOperations {

  private MaxComputeDatabaseOperations operations;
  private DataSource mockDataSource;
  private Connection mockConnection;
  private ResultSet mockResultSet;

  @BeforeEach
  void setUp() {
    operations = new MaxComputeDatabaseOperations();
    mockDataSource = mock(DataSource.class);
    mockConnection = mock(Connection.class);
    mockResultSet = mock(ResultSet.class);

    Map<String, String> conf = new HashMap<>();
    operations.initialize(mockDataSource, new MaxComputeExceptionConverter(), conf);
  }

  @Test
  void testCreate() throws SQLException {
    when(mockDataSource.getConnection()).thenReturn(mockConnection);
    when(mockConnection.getCatalog()).thenReturn("default");
    java.sql.Statement mockStatement = mock(java.sql.Statement.class);
    when(mockConnection.createStatement()).thenReturn(mockStatement);
    when(mockStatement.executeUpdate("CREATE SCHEMA test_schema")).thenReturn(0);

    // Should not throw exception
    Map<String, String> properties = new HashMap<>();
    operations.create("test_schema", null, properties);
  }

  @Test
  void testDelete() throws SQLException {
    java.sql.Statement mockStatement = mock(java.sql.Statement.class);
    when(mockDataSource.getConnection()).thenReturn(mockConnection);
    when(mockConnection.createStatement()).thenReturn(mockStatement);
    // Mock for enableSchemaMode
    when(mockStatement.execute("set odps.namespace.schema=true")).thenReturn(true);
    // Mock for listDatabases check in delete method
    when(mockStatement.executeQuery("SHOW SCHEMAS")).thenReturn(mockResultSet);
    when(mockResultSet.next()).thenReturn(true, false);
    when(mockResultSet.getString(1)).thenReturn("test_schema");
    // Mock for drop statement
    when(mockStatement.executeUpdate("DROP SCHEMA `test_schema`")).thenReturn(0);

    // Should not throw exception
    boolean result = operations.delete("test_schema", false);
    assertTrue(result);
  }

  @Test
  void testGenerateCreateDatabaseSql() {
    Map<String, String> properties = new HashMap<>();
    String sql = operations.generateCreateDatabaseSql("test_schema", "comment", properties);
    assertEquals("CREATE SCHEMA `test_schema`", sql);
  }

  @Test
  void testGenerateCreateDatabaseSqlWithPropertiesThrowsException() {
    Map<String, String> properties = new HashMap<>();
    properties.put("some_property", "value");
    assertThrows(
        UnsupportedOperationException.class,
        () -> operations.generateCreateDatabaseSql("test_schema", "comment", properties));
  }

  @Test
  void testGenerateDropDatabaseSql() throws SQLException {
    // Mock for cascade check - empty schema
    when(mockDataSource.getConnection()).thenReturn(mockConnection);

    String sql = operations.generateDropDatabaseSql("test_schema", false);
    assertEquals("DROP SCHEMA `test_schema`", sql);
  }

  @Test
  void testSupportSchemaComment() {
    assertFalse(operations.supportSchemaComment());
  }

  @Test
  void testCreateSysDatabaseNameSet() {
    assertTrue(operations.createSysDatabaseNameSet().contains("information_schema"));
    assertEquals(1, operations.createSysDatabaseNameSet().size());
  }

  @Test
  void testIsSystemDatabase() {
    assertTrue(operations.isSystemDatabase("information_schema"));
    assertTrue(operations.isSystemDatabase("INFORMATION_SCHEMA"));
    assertFalse(operations.isSystemDatabase("my_project"));
    assertFalse(operations.isSystemDatabase(null));
  }

  @Test
  void testListDatabases() throws SQLException {
    java.sql.Statement mockStatement = mock(java.sql.Statement.class);
    when(mockDataSource.getConnection()).thenReturn(mockConnection);
    when(mockConnection.createStatement()).thenReturn(mockStatement);
    when(mockStatement.executeQuery("SHOW SCHEMAS")).thenReturn(mockResultSet);
    // Simulate space-separated format: "default information_schema test_schema"
    when(mockResultSet.next()).thenReturn(true, false);
    when(mockResultSet.getString(1)).thenReturn("default information_schema test_schema");

    List<String> databases = operations.listDatabases();

    assertEquals(2, databases.size());
    assertTrue(databases.contains("default"));
    assertTrue(databases.contains("test_schema"));
    assertFalse(databases.contains("information_schema"));
  }

  @Test
  void testListDatabasesMultipleRows() throws SQLException {
    java.sql.Statement mockStatement = mock(java.sql.Statement.class);
    when(mockDataSource.getConnection()).thenReturn(mockConnection);
    when(mockConnection.createStatement()).thenReturn(mockStatement);
    when(mockStatement.executeQuery("SHOW SCHEMAS")).thenReturn(mockResultSet);
    // Simulate one schema per row format
    when(mockResultSet.next()).thenReturn(true, true, true, false);
    when(mockResultSet.getString(1)).thenReturn("default", "information_schema", "test_schema");

    List<String> databases = operations.listDatabases();

    assertEquals(2, databases.size());
    assertTrue(databases.contains("default"));
    assertTrue(databases.contains("test_schema"));
    assertFalse(databases.contains("information_schema"));
  }

  @Test
  void testLoad() throws SQLException {
    java.sql.Statement mockStatement = mock(java.sql.Statement.class);
    when(mockDataSource.getConnection()).thenReturn(mockConnection);
    when(mockConnection.createStatement()).thenReturn(mockStatement);
    when(mockStatement.executeQuery("SHOW SCHEMAS")).thenReturn(mockResultSet);
    when(mockResultSet.next()).thenReturn(true, false);
    when(mockResultSet.getString(1)).thenReturn("test_schema");

    JdbcSchema schema = operations.load("test_schema");

    assertNotNull(schema);
    assertEquals("test_schema", schema.name());
    assertNotNull(schema.properties());
    assertTrue(Objects.requireNonNull(schema.properties()).isEmpty());
  }

  @Test
  void testLoadThrowsNoSuchSchemaException() throws SQLException {
    java.sql.Statement mockStatement = mock(java.sql.Statement.class);
    when(mockDataSource.getConnection()).thenReturn(mockConnection);
    when(mockConnection.createStatement()).thenReturn(mockStatement);
    when(mockStatement.executeQuery("SHOW SCHEMAS")).thenReturn(mockResultSet);
    when(mockResultSet.next()).thenReturn(false);

    assertThrows(NoSuchSchemaException.class, () -> operations.load("non_existent_schema"));
  }
}
