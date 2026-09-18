/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity.TrustedMetadata;
import org.apache.gravitino.utils.jdbc.JdbcSqlScriptUtils;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.jdbc.JdbcTrustedMetadataStore.TableRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/** Tests the JDBC integrity store as a DAO on H2, which honours the DDL's cascading foreign key. */
public class TestJdbcTrustedMetadataStore {

  private static final String CATALOG = "catalog";
  private static final TableIdentifier TABLE = TableIdentifier.of(Namespace.of("ns"), "table");
  private static final String LOCATION = "s3://bucket/ns/table/metadata/v1.metadata.json";
  private static final String LOCATION_V2 = "s3://bucket/ns/table/metadata/v2.metadata.json";
  private static final TrustedMetadata TRUSTED_METADATA =
      TrustedMetadata.of("kms-key-1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  private static final TrustedMetadata UPDATED_METADATA =
      TrustedMetadata.of("kms-key-2", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=");
  private static final String POSTGRESQL_SCHEMA_RESOURCE =
      "/org/apache/gravitino/iceberg/jdbc/metadata-integrity/postgresql/schema-v1.sql";

  @Test
  void testStoreUsesCallerTransactionWithoutCommitting() throws Exception {
    String uri = newIsolatedTestDatabaseUri();
    JdbcTrustedMetadataStore store = newStore(CATALOG);

    try (Connection writer = DriverManager.getConnection(uri);
        Connection observer = DriverManager.getConnection(uri)) {
      provisionTestSchema(writer);
      writer.setAutoCommit(false);
      insertPointer(writer, CATALOG, TABLE, "TABLE");
      Assertions.assertEquals(1, store.insert(writer, TABLE, LOCATION, TRUSTED_METADATA));
      Assertions.assertTrue(store.load(writer, TABLE).orElseThrow().encrypted());
      Assertions.assertFalse(store.load(observer, TABLE).isPresent());

      writer.rollback();
      Assertions.assertFalse(store.load(observer, TABLE).isPresent());
    }
  }

  @Test
  void testCatalogNameScopesLoadAndUpdate() throws Exception {
    JdbcTrustedMetadataStore first = newStore("catalog_a");
    JdbcTrustedMetadataStore second = newStore("catalog_b");

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      provisionTestSchema(connection);
      insertPointer(connection, "catalog_a", TABLE, "TABLE");
      insertPointer(connection, "catalog_b", TABLE, "TABLE");
      Assertions.assertEquals(1, first.insert(connection, TABLE, LOCATION, TRUSTED_METADATA));
      Assertions.assertEquals(1, second.insert(connection, TABLE, LOCATION, TRUSTED_METADATA));

      Assertions.assertEquals(
          1,
          first.update(
              connection, TABLE, LOCATION, TRUSTED_METADATA, LOCATION_V2, UPDATED_METADATA));

      assertIntegrity(first.load(connection, TABLE).orElseThrow(), LOCATION_V2, UPDATED_METADATA);
      assertIntegrity(second.load(connection, TABLE).orElseThrow(), LOCATION, TRUSTED_METADATA);
    }
  }

  @Test
  void testUpdateReturnsZeroWhenExpectationDoesNotHold() throws Exception {
    JdbcTrustedMetadataStore store = newStore(CATALOG);

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      provisionTestSchema(connection);
      insertPointer(connection, CATALOG, TABLE, "TABLE");
      Assertions.assertEquals(1, store.insert(connection, TABLE, LOCATION, TRUSTED_METADATA));

      // Same location, stale key/hash: the CAS must not write.
      Assertions.assertEquals(
          0,
          store.update(
              connection, TABLE, LOCATION, UPDATED_METADATA, LOCATION_V2, UPDATED_METADATA));
      // Stale location with the current key/hash: the CAS must not write either.
      Assertions.assertEquals(
          0,
          store.update(
              connection, TABLE, LOCATION_V2, TRUSTED_METADATA, LOCATION_V2, UPDATED_METADATA));
      assertIntegrity(store.load(connection, TABLE).orElseThrow(), LOCATION, TRUSTED_METADATA);
    }
  }

  @Test
  void testLoadDistinguishesPlainAndEnrolledTables() throws Exception {
    JdbcTrustedMetadataStore store = newStore(CATALOG);

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      provisionTestSchema(connection);
      Assertions.assertFalse(store.load(connection, TABLE).isPresent());

      insertPointer(connection, CATALOG, TABLE, "TABLE");
      TableRecord plain = store.load(connection, TABLE).orElseThrow();
      Assertions.assertEquals(LOCATION, plain.metadataLocation());
      Assertions.assertFalse(plain.encrypted());
      Assertions.assertNull(plain.integrityMetadataLocation());
      Assertions.assertNull(plain.trustedMetadata().keyId());
      Assertions.assertNull(plain.trustedMetadata().metadataHash());

      Assertions.assertEquals(1, store.insert(connection, TABLE, LOCATION, TRUSTED_METADATA));
      TableRecord enrolled = store.load(connection, TABLE).orElseThrow();
      Assertions.assertTrue(enrolled.encrypted());
      Assertions.assertEquals(TRUSTED_METADATA.keyId(), enrolled.trustedMetadata().keyId());
      Assertions.assertEquals(
          TRUSTED_METADATA.metadataHash(), enrolled.trustedMetadata().metadataHash());

      // A pointer moved past the integrity row yields no trusted values but stays enrolled.
      movePointer(connection, TABLE, LOCATION_V2);
      TableRecord stale = store.load(connection, TABLE).orElseThrow();
      Assertions.assertEquals(LOCATION_V2, stale.metadataLocation());
      Assertions.assertEquals(LOCATION, stale.integrityMetadataLocation());
      Assertions.assertTrue(stale.encrypted());
      Assertions.assertNull(stale.trustedMetadata().keyId());
    }
  }

  @Test
  void testV1LoadIgnoresViewRowsButV0LoadCannot() throws Exception {
    JdbcTrustedMetadataStore v1 = newStore(CATALOG);
    JdbcTrustedMetadataStore v0 = new JdbcTrustedMetadataStore(CATALOG, JdbcUtil.SchemaVersion.V0);
    TableIdentifier view = TableIdentifier.of(Namespace.of("ns"), "view");
    TableIdentifier untyped = TableIdentifier.of(Namespace.of("ns"), "untyped");

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      provisionTestSchema(connection);
      insertPointer(connection, CATALOG, TABLE, "TABLE");
      insertPointer(connection, CATALOG, view, "VIEW");
      insertPointer(connection, CATALOG, untyped, null);

      Assertions.assertTrue(v1.load(connection, TABLE).isPresent());
      Assertions.assertTrue(v1.load(connection, untyped).isPresent());
      Assertions.assertFalse(v1.load(connection, view).isPresent());
      // Without the record-type filter a view's pointer would read as a plain table.
      Assertions.assertTrue(v0.load(connection, view).isPresent());
    }
  }

  @Test
  void testDdlCascadesPointerRenameAndDelete() throws Exception {
    JdbcTrustedMetadataStore store = newStore(CATALOG);
    TableIdentifier renamed = TableIdentifier.of(Namespace.of("other"), "renamed");

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri());
        Statement statement = connection.createStatement()) {
      provisionTestSchema(connection);
      insertPointer(connection, CATALOG, TABLE, "TABLE");
      Assertions.assertEquals(1, store.insert(connection, TABLE, LOCATION, TRUSTED_METADATA));

      statement.executeUpdate(
          "UPDATE iceberg_tables SET table_namespace = 'other', table_name = 'renamed'"
              + " WHERE catalog_name = 'catalog' AND table_namespace = 'ns' AND table_name = 'table'");
      Assertions.assertFalse(store.load(connection, TABLE).isPresent());
      assertIntegrity(store.load(connection, renamed).orElseThrow(), LOCATION, TRUSTED_METADATA);

      statement.executeUpdate("DELETE FROM iceberg_tables");
      Assertions.assertFalse(store.load(connection, renamed).isPresent());
      Assertions.assertEquals(0, count(connection, JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  @Test
  void testInsertWithoutPointerRowIsRejected() throws Exception {
    JdbcTrustedMetadataStore store = newStore(CATALOG);

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      provisionTestSchema(connection);
      Assertions.assertThrows(
          SQLException.class, () -> store.insert(connection, TABLE, LOCATION, TRUSTED_METADATA));
      Assertions.assertEquals(0, count(connection, JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  @Test
  void testDdlRequiresIcebergTablesFirst() throws Exception {
    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      // Reverse order (our script before Iceberg's, e.g. jdbc-initialize=false) must fail loudly.
      Assertions.assertThrows(
          SQLException.class,
          () -> JdbcSqlScriptUtils.executeSqlScript(connection, readPostgreSqlSchema()));
      Assertions.assertThrows(
          SQLException.class,
          () -> newStore(CATALOG).probe(reportingProduct(connection, "PostgreSQL")));
    }
  }

  @Test
  void testProbeRequiresEveryColumn() throws Exception {
    JdbcTrustedMetadataStore store = newStore(CATALOG);

    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri());
        Statement statement = connection.createStatement()) {
      Connection postgres = reportingProduct(connection, "PostgreSQL");
      Assertions.assertThrows(SQLException.class, () -> store.probe(postgres));
      provisionTestSchema(connection);
      Assertions.assertDoesNotThrow(() -> store.probe(postgres));

      statement.execute("ALTER TABLE iceberg_metadata_integrity DROP COLUMN metadata_hash");
      Assertions.assertThrows(SQLException.class, () -> store.probe(postgres));
    }
  }

  @Test
  void testProbeRejectsUnsupportedDatabaseEvenWhenTableExists() throws Exception {
    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      provisionTestSchema(connection);
      Assertions.assertDoesNotThrow(
          () -> newStore(CATALOG).probe(reportingProduct(connection, "PostgreSQL")));

      SQLException exception =
          Assertions.assertThrows(SQLException.class, () -> newStore(CATALOG).probe(connection));

      Assertions.assertTrue(
          exception.getMessage().contains("does not support database product H2"));
    }
  }

  @Test
  void testProvisionRejectsUnsupportedDatabase() throws Exception {
    try (Connection connection = DriverManager.getConnection(newIsolatedTestDatabaseUri())) {
      SQLException exception =
          Assertions.assertThrows(
              SQLException.class, () -> newStore(CATALOG).provision(connection));

      Assertions.assertTrue(
          exception.getMessage().contains("does not support database product H2"));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "PostgreSQL, CREATE TABLE IF NOT EXISTS iceberg_metadata_integrity",
    "MySQL, ENGINE=InnoDB"
  })
  void testProvisionRunsVersionedResourceForProduct(String product, String expectedDdl)
      throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
    Statement statement = Mockito.mock(Statement.class);
    Mockito.when(connection.getMetaData()).thenReturn(metadata);
    Mockito.when(metadata.getDatabaseProductName()).thenReturn(product);
    Mockito.when(connection.createStatement()).thenReturn(statement);

    newStore(CATALOG).provision(connection);

    Mockito.verify(statement).execute(Mockito.contains(expectedDdl));
    Mockito.verify(statement).execute(Mockito.contains("ON UPDATE CASCADE ON DELETE CASCADE"));
    Mockito.verify(connection, Mockito.never()).commit();
  }

  private static JdbcTrustedMetadataStore newStore(String catalogName) {
    return new JdbcTrustedMetadataStore(catalogName, JdbcUtil.SchemaVersion.V1);
  }

  /** Iceberg's V1 catalog table first, then our PostgreSQL script (H2 accepts both). */
  private static void provisionTestSchema(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(JdbcUtil.V0_CREATE_CATALOG_SQL);
      statement.execute(JdbcUtil.V1_UPDATE_CATALOG_SQL);
    }
    JdbcSqlScriptUtils.executeSqlScript(connection, readPostgreSqlSchema());
  }

  private static String readPostgreSqlSchema() throws Exception {
    try (InputStream input =
        TestJdbcTrustedMetadataStore.class.getResourceAsStream(POSTGRESQL_SCHEMA_RESOURCE)) {
      Assertions.assertNotNull(input, "Missing test schema resource " + POSTGRESQL_SCHEMA_RESOURCE);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void insertPointer(
      Connection connection, String catalogName, TableIdentifier identifier, @Nullable String type)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name,"
                + " metadata_location, iceberg_type) VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, catalogName);
      statement.setString(2, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(3, identifier.name());
      statement.setString(4, LOCATION);
      statement.setString(5, type);
      statement.executeUpdate();
    }
  }

  private static void movePointer(
      Connection connection, TableIdentifier identifier, String metadataLocation)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE iceberg_tables SET metadata_location = ? WHERE catalog_name = ?"
                + " AND table_namespace = ? AND table_name = ?")) {
      statement.setString(1, metadataLocation);
      statement.setString(2, CATALOG);
      statement.setString(3, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(4, identifier.name());
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  private static int count(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      Assertions.assertTrue(resultSet.next());
      return resultSet.getInt(1);
    }
  }

  private static void assertIntegrity(
      TableRecord record, String metadataLocation, TrustedMetadata expected) {
    Assertions.assertTrue(record.encrypted());
    Assertions.assertEquals(metadataLocation, record.integrityMetadataLocation());
    Assertions.assertEquals(expected.keyId(), record.keyId());
    Assertions.assertEquals(expected.metadataHash(), record.metadataHash());
  }

  /** The H2 connection with its metadata reporting {@code product}, so the dialect gate passes. */
  private static Connection reportingProduct(Connection delegate, String product) {
    return (Connection)
        Proxy.newProxyInstance(
            TestJdbcTrustedMetadataStore.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                if ("getMetaData".equals(method.getName())) {
                  DatabaseMetaData metadata = delegate.getMetaData();
                  return Proxy.newProxyInstance(
                      TestJdbcTrustedMetadataStore.class.getClassLoader(),
                      new Class<?>[] {DatabaseMetaData.class},
                      (metadataProxy, metadataMethod, metadataArguments) -> {
                        try {
                          if ("getDatabaseProductName".equals(metadataMethod.getName())) {
                            return product;
                          }
                          return metadataMethod.invoke(metadata, metadataArguments);
                        } catch (InvocationTargetException e) {
                          throw e.getCause();
                        }
                      });
                }
                return method.invoke(delegate, arguments);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static String newIsolatedTestDatabaseUri() {
    return String.format(
        "jdbc:h2:mem:trusted_store_%s;DB_CLOSE_DELAY=-1",
        UUID.randomUUID().toString().replace("-", ""));
  }
}
