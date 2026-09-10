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
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/** Unit tests for SqlServerSchemaOperations SQL generation and system schema filtering. */
public class TestSqlServerSchemaOperations {

  private final SqlServerSchemaOperations schemaOps = new SqlServerSchemaOperations();

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"probe", "a comment with 'quotes'"})
  void testCreateAcceptsOptionalComment(String comment) throws Exception {
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

  @Test
  void testCatalogCreateResponseIgnoresComment() {
    SqlServerSchemaOperations databaseOperation = Mockito.mock(SqlServerSchemaOperations.class);
    try (SqlServerCatalogOperations catalogOperations =
        new SqlServerCatalogOperations(
            new SqlServerExceptionConverter(),
            new SqlServerTypeConverter(),
            databaseOperation,
            Mockito.mock(SqlServerTableOperations.class),
            new SqlServerColumnDefaultValueConverter())) {
      JdbcSchema created =
          catalogOperations.createSchema(
              NameIdentifier.of("probe_schema"),
              "probe",
              StringIdentifier.newPropertiesWithId(
                  StringIdentifier.fromId(1L), Collections.emptyMap()));
      Assertions.assertEquals("probe_schema", created.name());
      Assertions.assertNull(created.comment());
      Mockito.verify(databaseOperation)
          .create(
              Mockito.eq("probe_schema"),
              Mockito.argThat(comment -> "".equals(StringIdentifier.removeIdFromComment(comment))),
              Mockito.eq(Collections.emptyMap()));
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
  void testGenerateDropDatabaseSqlCascadeIgnored() {
    // SQL Server does not support CASCADE; the flag is ignored
    String sql = schemaOps.generateDropDatabaseSql("test_schema", true);
    Assertions.assertEquals("DROP SCHEMA [test_schema]", sql);
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
}
