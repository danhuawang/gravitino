/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.oracle.converter.OracleExceptionConverter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestOracleDatabaseOperations {

  private static final String UNSUPPORTED_CREATE_MSG =
      "Oracle catalog does not support creating schemas (users).";
  private static final String UNSUPPORTED_DROP_MSG =
      "Oracle catalog does not support dropping schemas (users).";

  private OracleDatabaseOperations operations;
  private DataSource dataSource;
  private Connection connection;

  @BeforeEach
  void setUp() throws Exception {
    operations = new OracleDatabaseOperations();
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    operations.initialize(dataSource, new OracleExceptionConverter(), Collections.emptyMap());
  }

  @Test
  void testUnsupportedCreateAndDropDatabaseSql() {
    OracleDatabaseOperations forTest = new OracleDatabaseOperations();
    UnsupportedOperationException createException =
        assertThrows(
            UnsupportedOperationException.class,
            () -> forTest.generateCreateDatabaseSql("db1", null, Collections.emptyMap()));
    assertTrue(createException.getMessage().contains(UNSUPPORTED_CREATE_MSG));

    UnsupportedOperationException dropException =
        assertThrows(
            UnsupportedOperationException.class,
            () -> forTest.generateDropDatabaseSql("db1", false));
    assertTrue(dropException.getMessage().contains(UNSUPPORTED_DROP_MSG));
  }

  @Test
  void testListDatabasesFiltersSystemUsers() throws Exception {
    Statement statement = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME"))
        .thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, true, true, false);
    when(resultSet.getString("USERNAME")).thenReturn("SYS", "APP_USER", "SYSTEM");

    List<String> databases = operations.listDatabases();
    assertEquals(1, databases.size());
    assertEquals("APP_USER", databases.get(0));
  }

  @Test
  void testLoadReturnsSchemaWithoutComment() throws Exception {
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(connection.prepareStatement("SELECT USERNAME FROM ALL_USERS WHERE USERNAME = UPPER(?)"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getString("USERNAME")).thenReturn("APP_USER");

    assertEquals("APP_USER", operations.load("app_user").name());
    assertEquals("", operations.load("app_user").comment());
  }

  @Test
  void testLoadThrowsWhenSchemaNotFound() throws Exception {
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(connection.prepareStatement("SELECT USERNAME FROM ALL_USERS WHERE USERNAME = UPPER(?)"))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(false);

    assertThrows(NoSuchSchemaException.class, () -> operations.load("missing_user"));
  }

  @Test
  void testSchemaCommentAndSystemUsers() {
    OracleDatabaseOperations forTest = new OracleDatabaseOperations();
    assertFalse(forTest.supportSchemaComment());

    Set<String> systemUsers = forTest.createSysDatabaseNameSet();
    assertTrue(systemUsers.contains("sys"));
    assertTrue(systemUsers.contains("system"));
    assertTrue(systemUsers.contains("mdsys"));
    assertTrue(systemUsers.contains("anonymous"));
    assertTrue(systemUsers.contains("xs$null"));
    assertFalse(systemUsers.contains("app_user"));
  }
}
