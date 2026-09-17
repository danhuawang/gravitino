/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.datastrato.gravitino.catalog.sqlserver.SqlServerCatalogOperations;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerExceptionConverter;
import com.datastrato.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Unit tests for SqlServerSchemaOperations SQL generation and system schema filtering. */
public class TestSqlServerSchemaOperations {

  private final SqlServerSchemaOperations schemaOps = new SqlServerSchemaOperations();

  @ParameterizedTest
  @NullAndEmptySource
  void testCreateWithoutComment(String comment) throws Exception {
    DataSource dataSource = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    Statement statement = Mockito.mock(Statement.class);
    Mockito.when(dataSource.getConnection()).thenReturn(connection);
    Mockito.when(connection.createStatement()).thenReturn(statement);
    schemaOps.initialize(
        dataSource,
        new SqlServerExceptionConverter(),
        Collections.singletonMap("jdbc-database", "test_database"));

    schemaOps.create("probe_schema", comment, Collections.emptyMap());

    Mockito.verify(statement).executeUpdate("CREATE SCHEMA [probe_schema] AUTHORIZATION [dbo]");
    Mockito.verify(statement).close();
    Mockito.verify(connection).close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"probe", "a comment with 'quotes'", " "})
  void testCreateRejectsCommentBeforeOpeningConnection(String comment) {
    DataSource dataSource = Mockito.mock(DataSource.class);
    schemaOps.initialize(
        dataSource,
        new SqlServerExceptionConverter(),
        Collections.singletonMap("jdbc-database", "test_database"));
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> schemaOps.create("probe_schema", comment, Collections.emptyMap()));
    Mockito.verifyNoInteractions(dataSource);
  }

  @Test
  void testCatalogCreateRejectsComment() {
    DataSource dataSource = Mockito.mock(DataSource.class);
    schemaOps.initialize(
        dataSource,
        new SqlServerExceptionConverter(),
        Collections.singletonMap("jdbc-database", "test_database"));
    try (SqlServerCatalogOperations catalogOperations =
        new SqlServerCatalogOperations(
            new SqlServerExceptionConverter(),
            new SqlServerTypeConverter(),
            schemaOps,
            Mockito.mock(SqlServerTableOperations.class),
            new SqlServerColumnDefaultValueConverter())) {
      Assertions.assertThrows(
          UnsupportedOperationException.class,
          () ->
              catalogOperations.createSchema(
                  NameIdentifier.of("probe_schema"),
                  "probe",
                  StringIdentifier.newPropertiesWithId(
                      StringIdentifier.fromId(1L), Collections.emptyMap())));
      Mockito.verifyNoInteractions(dataSource);
    }
  }

  @Test
  void testSchemaExistsMatchesPhysicalCase() throws SQLException {
    Connection connection = Mockito.mock(Connection.class);
    PreparedStatement statement = Mockito.mock(PreparedStatement.class);
    ResultSet result = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
    Mockito.when(statement.executeQuery()).thenReturn(result);
    Mockito.when(result.next()).thenReturn(true, false);
    Mockito.when(result.getString(1)).thenReturn("MixedSchema");
    Assertions.assertTrue(schemaOps.schemaExists(connection, "mixedschema"));
    Mockito.verify(statement).setString(1, "mixedschema");
    Mockito.verify(result).close();
    Mockito.verify(statement).close();
  }

  @Test
  void testGenerateCreateDatabaseSql() {
    String sql = schemaOps.generateCreateDatabaseSql("test_schema", null, null);
    Assertions.assertEquals("CREATE SCHEMA [test_schema] AUTHORIZATION [dbo]", sql);
  }

  @Test
  void testGenerateCreateDatabaseSqlWithSpecialChars() {
    String sql = schemaOps.generateCreateDatabaseSql("my_schema#1", null, null);
    Assertions.assertEquals("CREATE SCHEMA [my_schema#1] AUTHORIZATION [dbo]", sql);
  }

  @Test
  void testGenerateCreateDatabaseSqlRejectsProperties() {
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () ->
            schemaOps.generateCreateDatabaseSql(
                "test_schema", null, Collections.singletonMap("key", "value")));
  }

  @Test
  void testGenerateDropDatabaseSql() {
    String sql = schemaOps.generateDropDatabaseSql("test_schema", false);
    Assertions.assertEquals("DROP SCHEMA [test_schema]", sql);
  }

  @Test
  void testGenerateDropDatabaseSqlEscapesIdentifier() {
    String sql = schemaOps.generateDropDatabaseSql("a]b", false);
    Assertions.assertEquals("DROP SCHEMA [a]]b]", sql);
  }

  @Test
  void testSystemSchemaSetContainsAllSystemSchemas() {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertEquals(12, sysSchemas.size());

    // Verify all 12 system schemas are present
    Assertions.assertTrue(sysSchemas.contains("guest"));
    Assertions.assertTrue(sysSchemas.contains("information_schema"));
    Assertions.assertTrue(sysSchemas.contains("sys"));
    Assertions.assertTrue(sysSchemas.contains("db_owner"));
    Assertions.assertTrue(sysSchemas.contains("db_accessadmin"));
    Assertions.assertTrue(sysSchemas.contains("db_securityadmin"));
    Assertions.assertTrue(sysSchemas.contains("db_ddladmin"));
    Assertions.assertTrue(sysSchemas.contains("db_backupoperator"));
    Assertions.assertTrue(sysSchemas.contains("db_datareader"));
    Assertions.assertTrue(sysSchemas.contains("db_datawriter"));
    Assertions.assertTrue(sysSchemas.contains("db_denydatareader"));
    Assertions.assertTrue(sysSchemas.contains("db_denydatawriter"));
  }

  @Test
  void testSystemSchemaSetDoesNotContainDbo() {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertFalse(sysSchemas.contains("dbo"));
  }

  @Test
  void testSupportSchemaComment() {
    Assertions.assertFalse(schemaOps.supportSchemaComment());
  }

  @Test
  void testCascadeDropsDependenciesAndCommits() throws Exception {
    Connection connection = mockDropConnection();
    Statement statement = connection.createStatement();
    mockObjects(connection, new String[][] {{"t]1", "U"}, {"v1", "V"}});
    PreparedStatement dependencyStatement = Mockito.mock(PreparedStatement.class);
    ResultSet dependencies = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("sys.sql_expression_dependencies")))
        .thenReturn(dependencyStatement);
    Mockito.when(dependencyStatement.executeQuery()).thenReturn(dependencies);
    Mockito.when(dependencies.next()).thenReturn(true, false);
    Mockito.when(dependencies.getInt(1)).thenReturn(2);
    Mockito.when(dependencies.getInt(2)).thenReturn(1);
    Assertions.assertTrue(schemaOps.delete("probe", true));
    InOrder order = Mockito.inOrder(statement);
    order.verify(statement).executeUpdate("DROP VIEW [probe].[v1]");
    order.verify(statement).executeUpdate("DROP TABLE [probe].[t]]1]");
    Mockito.verify(statement).executeUpdate("DROP SCHEMA [probe]");
    Mockito.verify(connection).setAutoCommit(false);
    Mockito.verify(connection).commit();
    Mockito.verify(connection, Mockito.never()).rollback();
  }

  @Test
  void testNonCascadeReturnsNonEmptyAndRollsBack() throws Exception {
    Connection connection = mockDropConnection();
    Statement dropStatement = connection.createStatement();
    Mockito.doThrow(new SQLException("referenced by t1", "S0001", 3729))
        .when(dropStatement)
        .executeUpdate("DROP SCHEMA [probe]");
    NonEmptySchemaException error =
        Assertions.assertThrows(
            NonEmptySchemaException.class, () -> schemaOps.delete("probe", false));
    Assertions.assertTrue(error.getMessage().contains("referenced by t1"));
    Mockito.verify(connection, Mockito.never()).prepareStatement(Mockito.anyString());
    Mockito.verify(connection).rollback();
    Mockito.verify(connection, Mockito.never()).commit();
  }

  @Test
  void testCascadeExternalDependencyRollsBackEarlierDrops() throws Exception {
    Connection connection = mockDropConnection();
    Statement dropStatement = connection.createStatement();
    mockObjects(connection, new String[][] {{"t1", "U"}, {"t2", "U"}});
    Mockito.doThrow(new SQLException("external reference", "S0001", 3726))
        .when(dropStatement)
        .executeUpdate("DROP TABLE [probe].[t2]");
    Assertions.assertThrows(NonEmptySchemaException.class, () -> schemaOps.delete("probe", true));
    Mockito.verify(connection.createStatement()).executeUpdate("DROP TABLE [probe].[t1]");
    Mockito.verify(connection).rollback();
    Mockito.verify(connection, Mockito.never()).commit();
    Mockito.verify(connection.createStatement(), Mockito.never())
        .executeUpdate("DROP SCHEMA [probe]");
  }

  @Test
  void testCascadePermissionFailureIsNotRetried() throws Exception {
    Connection connection = mockDropConnection();
    Statement dropStatement = connection.createStatement();
    mockObjects(connection, new String[][] {{"t1", "U"}});
    Mockito.doThrow(new SQLException("denied", "S0001", 229))
        .when(dropStatement)
        .executeUpdate("DROP TABLE [probe].[t1]");
    Assertions.assertThrows(ForbiddenException.class, () -> schemaOps.delete("probe", true));
    Mockito.verify(connection.createStatement()).executeUpdate("DROP TABLE [probe].[t1]");
    Mockito.verify(connection).rollback();
  }

  @Test
  void testCascadeRejectsUnknownObjectsBeforeDropping() throws Exception {
    Connection connection = mockDropConnection();
    mockObjects(connection, new String[][] {{"t1", "U"}, {"unknown", "XX"}});
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> schemaOps.delete("probe", true));
    Mockito.verify(connection.createStatement(), Mockito.never())
        .executeUpdate(Mockito.anyString());
    Mockito.verify(connection).rollback();
  }

  @Test
  void testCascadeRejectsDependencyCycleBeforeDropping() throws Exception {
    Connection connection = mockDropConnection();
    mockObjects(connection, new String[][] {{"v1", "V"}, {"v2", "V"}});
    PreparedStatement statement = Mockito.mock(PreparedStatement.class);
    ResultSet dependencies = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("sys.sql_expression_dependencies")))
        .thenReturn(statement);
    Mockito.when(statement.executeQuery()).thenReturn(dependencies);
    Mockito.when(dependencies.next()).thenReturn(true, true, false);
    Mockito.when(dependencies.getInt(1)).thenReturn(1, 2);
    Mockito.when(dependencies.getInt(2)).thenReturn(2, 1);
    Assertions.assertThrows(NonEmptySchemaException.class, () -> schemaOps.delete("probe", true));
    Mockito.verify(connection.createStatement(), Mockito.never())
        .executeUpdate(Mockito.anyString());
    Mockito.verify(connection).rollback();
  }

  @Test
  void testCascadeDropsForeignKeysBeforeTables() throws Exception {
    Connection connection = mockDropConnection();
    mockObjects(connection, new String[][] {{"t1", "U"}});
    PreparedStatement statement = Mockito.mock(PreparedStatement.class);
    ResultSet keys = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("sys.foreign_keys")))
        .thenReturn(statement);
    Mockito.when(statement.executeQuery()).thenReturn(keys);
    Mockito.when(keys.next()).thenReturn(true, false);
    Mockito.when(keys.getString(1)).thenReturn("t1");
    Mockito.when(keys.getString(2)).thenReturn("fk]self");
    Assertions.assertTrue(schemaOps.delete("probe", true));
    InOrder order = Mockito.inOrder(connection.createStatement());
    order
        .verify(connection.createStatement())
        .executeUpdate("ALTER TABLE [probe].[t1] DROP CONSTRAINT [fk]]self]");
    order.verify(connection.createStatement()).executeUpdate("DROP TABLE [probe].[t1]");
  }

  @Test
  void testCreateEscapesSchemaIdentifier() throws Exception {
    Connection connection = mockDropConnection();
    schemaOps.create("a]b", null, Collections.emptyMap());
    Mockito.verify(connection.createStatement())
        .executeUpdate("CREATE SCHEMA [a]]b] AUTHORIZATION [dbo]");
  }

  @Test
  void testCascadeOrdersTableTypesWithRoutinesAndAliasTypes() throws Exception {
    Connection connection = mockDropConnection();
    mockObjects(connection, new String[][] {{"fn", "FN"}, {"proc", "P"}, {"t", "U"}});
    PreparedStatement tableTypes = Mockito.mock(PreparedStatement.class);
    ResultSet types = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("FROM sys.table_types")))
        .thenReturn(tableTypes);
    Mockito.when(tableTypes.executeQuery()).thenReturn(types);
    Mockito.when(types.next()).thenReturn(true, false);
    Mockito.when(types.getString(1)).thenReturn("row]type");
    Mockito.when(types.getInt(2)).thenReturn(4);
    PreparedStatement aliases = Mockito.mock(PreparedStatement.class);
    ResultSet aliasTypes = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("FROM sys.types")))
        .thenReturn(aliases);
    Mockito.when(aliases.executeQuery()).thenReturn(aliasTypes);
    Mockito.when(aliasTypes.next()).thenReturn(true, false);
    Mockito.when(aliasTypes.getString(1)).thenReturn("alias");
    PreparedStatement dependencies = Mockito.mock(PreparedStatement.class);
    ResultSet edges = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("sys.sql_expression_dependencies")))
        .thenReturn(dependencies);
    Mockito.when(dependencies.executeQuery()).thenReturn(edges);
    // Procedure -> table type and table -> function, including duplicate and self edges.
    Mockito.when(edges.next()).thenReturn(true, true, true, true, false);
    Mockito.when(edges.getInt(1)).thenReturn(2, 3, 3, 3);
    Mockito.when(edges.getInt(2)).thenReturn(4, 1, 1, 3);

    Assertions.assertTrue(schemaOps.delete("probe", true));
    Statement statement = connection.createStatement();
    InOrder order = Mockito.inOrder(statement, connection);
    order.verify(statement).executeUpdate("DROP PROCEDURE [probe].[proc]");
    order.verify(statement).executeUpdate("DROP TABLE [probe].[t]");
    order.verify(statement).executeUpdate("DROP TYPE [probe].[row]]type]");
    order.verify(statement).executeUpdate("DROP FUNCTION [probe].[fn]");
    order.verify(statement).executeUpdate("DROP TYPE [probe].[alias]");
    order.verify(statement).executeUpdate("DROP SCHEMA [probe]");
    order.verify(connection).commit();
    Mockito.verify(connection).prepareStatement(Mockito.contains("is_table_type = 0"));
  }

  @Test
  void testRollbackFailurePreservesOriginalError() throws Exception {
    Connection connection = mockDropConnection();
    SQLException failure = new SQLException("referenced by t1", "S0001", 3729);
    SQLException rollbackFailure = new SQLException("rollback failed");
    Statement statement = connection.createStatement();
    Mockito.doThrow(failure).when(statement).executeUpdate("DROP SCHEMA [probe]");
    Mockito.doThrow(rollbackFailure).when(connection).rollback();
    NonEmptySchemaException error =
        Assertions.assertThrows(
            NonEmptySchemaException.class, () -> schemaOps.delete("probe", false));
    Assertions.assertSame(failure, error.getCause());
    Assertions.assertArrayEquals(new Throwable[] {rollbackFailure}, failure.getSuppressed());
    Mockito.verify(connection).close();
    Mockito.verify(connection, Mockito.never()).commit();
  }

  private Connection mockDropConnection() throws SQLException {
    DataSource source = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(source.getConnection()).thenReturn(connection);
    Mockito.when(connection.createStatement()).thenReturn(Mockito.mock(Statement.class));
    PreparedStatement emptyStatement = Mockito.mock(PreparedStatement.class);
    Mockito.when(emptyStatement.executeQuery()).thenReturn(Mockito.mock(ResultSet.class));
    Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(emptyStatement);
    schemaOps.initialize(
        source,
        new SqlServerExceptionConverter(),
        Collections.singletonMap("jdbc-database", "test_database"));
    return connection;
  }

  private void mockObjects(Connection connection, String[][] objects) throws SQLException {
    PreparedStatement statement = Mockito.mock(PreparedStatement.class);
    ResultSet result = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement(Mockito.contains("FROM sys.objects")))
        .thenReturn(statement);
    Mockito.when(statement.executeQuery()).thenReturn(result);
    int[] row = {-1};
    Mockito.when(result.next()).thenAnswer(invocation -> ++row[0] < objects.length);
    Mockito.when(result.getInt(3)).thenAnswer(invocation -> row[0] + 1);
    Mockito.when(result.getString(Mockito.anyInt()))
        .thenAnswer(invocation -> objects[row[0]][invocation.<Integer>getArgument(0) - 1]);
  }
}
