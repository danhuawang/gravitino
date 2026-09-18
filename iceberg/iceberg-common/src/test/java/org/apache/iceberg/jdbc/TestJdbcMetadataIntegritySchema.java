/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Tests the MySQL InnoDB inspector over both catalog tables. */
class TestJdbcMetadataIntegritySchema {

  @Test
  void testMySqlStorageValidationAcceptsInnoDbTables() throws Exception {
    Connection connection = mySqlStorageConnection("InnoDB", "InnoDB");

    Assertions.assertDoesNotThrow(
        () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));
  }

  @Test
  void testMySqlStorageValidationRequiresBothTablesToUseInnoDb() throws Exception {
    Connection connection = mySqlStorageConnection("InnoDB", "MyISAM");

    SQLException exception =
        Assertions.assertThrows(
            SQLException.class,
            () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));

    Assertions.assertTrue(exception.getMessage().contains(JdbcTrustedMetadataStore.TABLE_NAME));
    Assertions.assertTrue(exception.getMessage().contains("MyISAM"));
  }

  @Test
  void testMySqlStorageValidationRejectsMyIsamCatalogTable() throws Exception {
    Connection connection = mySqlStorageConnection("MyISAM", "InnoDB");

    SQLException exception =
        Assertions.assertThrows(
            SQLException.class,
            () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));

    Assertions.assertTrue(exception.getMessage().contains(JdbcUtil.CATALOG_TABLE_VIEW_NAME));
    Assertions.assertTrue(exception.getMessage().contains("MyISAM"));
  }

  @Test
  void testMySqlStorageValidationReportsMissingIntegrityTable() throws Exception {
    Connection connection = mySqlStorageConnection("InnoDB", null);

    SQLException exception =
        Assertions.assertThrows(
            SQLException.class,
            () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));

    Assertions.assertTrue(exception.getMessage().contains(JdbcTrustedMetadataStore.TABLE_NAME));
    Assertions.assertTrue(exception.getMessage().contains("<missing>"));
  }

  @Test
  void testStorageValidationQueriesNothingOnOtherProducts() throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
    Mockito.when(connection.getMetaData()).thenReturn(metadata);
    Mockito.when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");

    Assertions.assertDoesNotThrow(
        () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));

    Mockito.verify(connection, Mockito.never()).prepareStatement(Mockito.anyString());
    Mockito.verify(connection, Mockito.never()).createStatement();
  }

  /**
   * Mocks the {@code information_schema.TABLES} read; a {@code null} integrity engine models a
   * missing {@code iceberg_metadata_integrity} table (only the catalog table row comes back).
   */
  private static Connection mySqlStorageConnection(
      String catalogTableEngine, String integrityTableEngine) throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
    PreparedStatement statement = Mockito.mock(PreparedStatement.class);
    ResultSet resultSet = Mockito.mock(ResultSet.class);
    Mockito.when(connection.getMetaData()).thenReturn(metadata);
    Mockito.when(metadata.getDatabaseProductName()).thenReturn("MySQL");
    Mockito.when(connection.prepareStatement(Mockito.contains("information_schema.TABLES")))
        .thenReturn(statement);
    Mockito.when(statement.executeQuery()).thenReturn(resultSet);
    if (integrityTableEngine == null) {
      Mockito.when(resultSet.next()).thenReturn(true, false);
      Mockito.when(resultSet.getString("TABLE_NAME")).thenReturn(JdbcUtil.CATALOG_TABLE_VIEW_NAME);
      Mockito.when(resultSet.getString("ENGINE")).thenReturn(catalogTableEngine);
      return connection;
    }
    Mockito.when(resultSet.next()).thenReturn(true, true, false);
    Mockito.when(resultSet.getString("TABLE_NAME"))
        .thenReturn(JdbcUtil.CATALOG_TABLE_VIEW_NAME, JdbcTrustedMetadataStore.TABLE_NAME);
    Mockito.when(resultSet.getString("ENGINE"))
        .thenReturn(catalogTableEngine, integrityTableEngine);
    return connection;
  }
}
