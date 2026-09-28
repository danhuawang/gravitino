/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity.TrustedMetadata;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.jdbc.JdbcTrustedMetadataStore.TableRecord;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Contract of the catalog-local integrity schema and store on real PostgreSQL 13 and MySQL 8.0.
 *
 * <p>Covers provision/probe, DDL and foreign-key behavior, plus catalog rename and drop while the
 * integrity store is unavailable. Every case runs in its own database scope (a schema on
 * PostgreSQL, a database on MySQL) on a container that is started once per engine.
 */
@Tag("gravitino-docker-test")
class TestJdbcIntegrityContract {

  private static final String POSTGRESQL = "postgresql";
  private static final String MYSQL = "mysql";
  private static final String MYSQL_ROOT_USER = "root";
  private static final String CATALOG = "first";
  private static final TableIdentifier TABLE = TableIdentifier.of("ns", "table");
  private static final TableIdentifier SIBLING = TableIdentifier.of("ns", "sibling");
  private static final TableIdentifier RENAMED = TableIdentifier.of("other_ns", "renamed");
  private static final String LOCATION = "s3://bucket/ns/table/metadata/v1.metadata.json";
  private static final TrustedMetadata TRUSTED =
      TrustedMetadata.of("key-1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
  private static final Map<String, Integer> DECLARED_COLUMN_LENGTHS =
      Map.of(
          "catalog_name", 255,
          "table_namespace", 255,
          "table_name", 255,
          "metadata_location", 1000,
          "table_key_id", 2048,
          "hash_algorithm", 32,
          "metadata_hash", 128);
  private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new ConcurrentHashMap<>();
  private static final AtomicInteger SCOPES = new AtomicInteger();

  @TempDir Path warehouse;

  private final List<Scope> scopes = new ArrayList<>();

  @AfterEach
  void dropScopes() throws SQLException {
    for (Scope scope : scopes) {
      dropScope(scope);
    }
    scopes.clear();
  }

  @AfterAll
  static void stopContainers() {
    CONTAINERS.values().forEach(container -> container.stop());
    CONTAINERS.clear();
  }

  @ParameterizedTest(name = "{0} provision after Iceberg DDL")
  @ValueSource(strings = {POSTGRESQL, MYSQL})
  void testProvisionCreatesCascadingForeignKeyAndSurvivesV1Upgrade(String engine) throws Exception {
    Scope scope = newScope(engine, "");
    JdbcTrustedMetadataStore store = newStore(JdbcUtil.SchemaVersion.V0);
    try (Connection connection = scope.connect()) {
      execute(connection, JdbcUtil.V0_CREATE_CATALOG_SQL);

      store.provision(connection);

      store.probe(connection);
      JdbcMetadataIntegritySchema.validateAtomicStorage(connection);
      Assertions.assertEquals(1, cascadingForeignKeys(connection, scope));
      assertDeclaredColumns(connection, scope);

      // Re-provisioning is idempotent and keeps rows.
      insertPointer(connection, TABLE);
      Assertions.assertEquals(1, store.insert(connection, TABLE, LOCATION, TRUSTED));
      store.provision(connection);
      assertIntegrity(store.load(connection, TABLE).orElseThrow(), LOCATION, TRUSTED);

      // Iceberg's V0 -> V1 upgrade keeps the foreign key and its rules.
      execute(connection, JdbcUtil.V1_UPDATE_CATALOG_SQL);
      Assertions.assertEquals(1, cascadingForeignKeys(connection, scope));
      assertIntegrity(
          newStore(JdbcUtil.SchemaVersion.V1).load(connection, TABLE).orElseThrow(),
          LOCATION,
          TRUSTED);
    }
  }

  @Test
  void testMySqlRejectsMyIsamTables() throws Exception {
    // latin1 keeps the 3 x VARCHAR(255) primary key under MyISAM's index limit.
    Scope scope = newScope(MYSQL, " CHARACTER SET latin1");
    JdbcTrustedMetadataStore store = newStore(JdbcUtil.SchemaVersion.V1);
    try (Connection connection = scope.connect()) {
      // MyISAM parent: the DDL's foreign key cannot be created (1824 ER_FK_CANNOT_OPEN_PARENT).
      execute(connection, JdbcUtil.V0_CREATE_CATALOG_SQL + " ENGINE=MyISAM");
      SQLException parent =
          Assertions.assertThrows(SQLException.class, () -> store.provision(connection));
      Assertions.assertEquals(1824, parent.getErrorCode());
      Assertions.assertThrows(SQLException.class, () -> store.probe(connection));
      SQLException inspected =
          Assertions.assertThrows(
              SQLException.class,
              () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));
      Assertions.assertTrue(inspected.getMessage().contains(JdbcUtil.CATALOG_TABLE_VIEW_NAME));
      Assertions.assertTrue(inspected.getMessage().contains("MyISAM"));

      // InnoDB parent, then the child converted behind the catalog's back: the inspector rejects.
      execute(connection, "ALTER TABLE iceberg_tables ENGINE=InnoDB");
      store.provision(connection);
      Assertions.assertDoesNotThrow(
          () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));
      execute(
          connection,
          "ALTER TABLE iceberg_metadata_integrity DROP FOREIGN KEY "
              + foreignKeyName(connection, scope));
      execute(connection, "ALTER TABLE iceberg_metadata_integrity ENGINE=MyISAM");
      SQLException child =
          Assertions.assertThrows(
              SQLException.class,
              () -> JdbcMetadataIntegritySchema.validateAtomicStorage(connection));
      Assertions.assertTrue(child.getMessage().contains(JdbcTrustedMetadataStore.TABLE_NAME));
      Assertions.assertTrue(child.getMessage().contains("MyISAM"));
    }
  }

  /**
   * Pre-read gate, encrypted side: an enrolled table is loaded through the verifying operations
   * before upstream's statement runs, so an unreachable store refuses the rename and the drop and
   * leaves both rows in place; once the store is back, the rename carries the integrity row.
   */
  @ParameterizedTest(name = "{0} encrypted table fails closed while the store is unavailable")
  @ValueSource(strings = {POSTGRESQL, MYSQL})
  void testEncryptedTableRenameAndDropFailClosedWhenStoreIsUnavailable(String engine)
      throws Exception {
    Scope scope = newScope(engine, "");
    JdbcCatalogWithMetadataLocationSupport catalog = newCatalog(scope, true, true);
    try (Connection connection = scope.connect()) {
      catalog.createNamespace(TABLE.namespace());
      catalog.createNamespace(RENAMED.namespace());
      BaseTable encrypted = createEncryptedTable(catalog, TABLE);
      String location = encrypted.operations().current().metadataFileLocation();

      execute(connection, "ALTER TABLE iceberg_metadata_integrity RENAME TO integrity_unavailable");
      Assertions.assertThrows(
          UncheckedSQLException.class, () -> catalog.renameTable(TABLE, RENAMED));
      Assertions.assertThrows(UncheckedSQLException.class, () -> catalog.dropTable(TABLE, false));
      Assertions.assertEquals(1, pointerRows(connection, TABLE));
      execute(connection, "ALTER TABLE integrity_unavailable RENAME TO iceberg_metadata_integrity");

      catalog.renameTable(TABLE, RENAMED);
      Assertions.assertEquals(
          location,
          newStore(JdbcUtil.SchemaVersion.V1)
              .load(connection, RENAMED)
              .orElseThrow()
              .integrityMetadataLocation());
    } finally {
      catalog.close();
    }
  }

  /**
   * Pre-read gate, plain side: a table without an integrity row is renamed and dropped without any
   * metadata file read, both by a catalog whose store was unavailable at startup (plain upstream
   * mode) and by an integrity-aware catalog. The deleted metadata file proves it; the same deletion
   * refuses an encrypted table.
   */
  @ParameterizedTest(name = "{0} plain table skips the pre-read while the store is unavailable")
  @ValueSource(strings = {POSTGRESQL, MYSQL})
  void testPlainTableRenameAndDropSkipPreReadWhenStoreIsUnavailable(String engine)
      throws Exception {
    Scope scope = newScope(engine, "");
    try (Connection connection = scope.connect()) {
      execute(connection, JdbcUtil.V0_CREATE_CATALOG_SQL);
      execute(connection, JdbcUtil.V1_UPDATE_CATALOG_SQL);
      execute(connection, JdbcUtil.CREATE_NAMESPACE_PROPERTIES_TABLE_SQL);
    }
    // No integrity table and no governed encryption: the probe fails and the catalog runs plain.
    JdbcCatalogWithMetadataLocationSupport plainMode = newCatalog(scope, false, false);
    try {
      Assertions.assertFalse(plainMode.supportsAtomicMetadataIntegrity());
      plainMode.createNamespace(TABLE.namespace());
      plainMode.createNamespace(RENAMED.namespace());
      BaseTable table = (BaseTable) plainMode.createTable(TABLE, SCHEMA);
      table.io().deleteFile(table.operations().current().metadataFileLocation());

      plainMode.renameTable(TABLE, RENAMED);
      Assertions.assertTrue(plainMode.dropTable(RENAMED, false));
    } finally {
      plainMode.close();
    }

    JdbcCatalogWithMetadataLocationSupport catalog = newCatalog(scope, true, true);
    try (Connection connection = scope.connect()) {
      Assertions.assertTrue(catalog.supportsAtomicMetadataIntegrity());
      BaseTable plain = (BaseTable) catalog.createTable(TABLE, SCHEMA);
      BaseTable encrypted = createEncryptedTable(catalog, SIBLING);
      plain.io().deleteFile(plain.operations().current().metadataFileLocation());
      encrypted.io().deleteFile(encrypted.operations().current().metadataFileLocation());

      catalog.renameTable(TABLE, RENAMED);
      Assertions.assertTrue(catalog.dropTable(RENAMED, false));
      Assertions.assertThrows(NotFoundException.class, () -> catalog.renameTable(SIBLING, RENAMED));
      Assertions.assertThrows(NotFoundException.class, () -> catalog.dropTable(SIBLING, false));
      Assertions.assertEquals(1, pointerRows(connection, SIBLING));
      Assertions.assertEquals(1, integrityRows(connection, SIBLING));
    } finally {
      catalog.close();
    }
  }

  /** The DDL is the schema: every declared column exists as NOT NULL VARCHAR of its length. */
  private static void assertDeclaredColumns(Connection connection, Scope scope)
      throws SQLException {
    Map<String, String> types = new HashMap<>();
    Map<String, Integer> lengths = new HashMap<>();
    Map<String, String> nullability = new HashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT column_name, data_type, character_maximum_length, is_nullable"
                + " FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
      statement.setString(1, scope.name);
      statement.setString(2, JdbcTrustedMetadataStore.TABLE_NAME);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String column = resultSet.getString(1);
          types.put(column, resultSet.getString(2).toLowerCase(Locale.ROOT));
          lengths.put(column, resultSet.getInt(3));
          nullability.put(column, resultSet.getString(4));
        }
      }
    }
    Assertions.assertEquals(DECLARED_COLUMN_LENGTHS.keySet(), types.keySet());
    DECLARED_COLUMN_LENGTHS.forEach(
        (column, length) -> {
          String type = types.get(column);
          Assertions.assertTrue(
              "varchar".equals(type) || "character varying".equals(type), column + ": " + type);
          Assertions.assertEquals(length, lengths.get(column), column);
          Assertions.assertEquals("NO", nullability.get(column), column);
        });
  }

  private static int cascadingForeignKeys(Connection connection, Scope scope) throws SQLException {
    String sql =
        MYSQL.equals(scope.engine)
            ? "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'iceberg_metadata_integrity'"
                + " AND REFERENCED_TABLE_NAME = 'iceberg_tables'"
                + " AND UPDATE_RULE = 'CASCADE' AND DELETE_RULE = 'CASCADE'"
            : "SELECT COUNT(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                + " WHERE c.contype = 'f' AND n.nspname = ?"
                + " AND c.conrelid = 'iceberg_metadata_integrity'::regclass"
                + " AND c.confrelid = 'iceberg_tables'::regclass"
                + " AND c.confupdtype = 'c' AND c.confdeltype = 'c'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, scope.name);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static String foreignKeyName(Connection connection, Scope scope) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = 'iceberg_metadata_integrity'")) {
      statement.setString(1, scope.name);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertTrue(resultSet.next());
        return resultSet.getString(1);
      }
    }
  }

  private static void insertPointer(Connection connection, TableIdentifier identifier)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO iceberg_tables"
                + " (catalog_name, table_namespace, table_name, metadata_location)"
                + " VALUES (?, ?, ?, ?)")) {
      statement.setString(1, CATALOG);
      statement.setString(2, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(3, identifier.name());
      statement.setString(4, LOCATION);
      statement.executeUpdate();
    }
  }

  private static int integrityRows(Connection connection, TableIdentifier identifier)
      throws SQLException {
    return rows(connection, JdbcTrustedMetadataStore.TABLE_NAME, identifier);
  }

  private static int pointerRows(Connection connection, TableIdentifier identifier)
      throws SQLException {
    return rows(connection, JdbcUtil.CATALOG_TABLE_VIEW_NAME, identifier);
  }

  private static int rows(Connection connection, String table, TableIdentifier identifier)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM "
                + table
                + " WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?")) {
      statement.setString(1, CATALOG);
      statement.setString(2, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(3, identifier.name());
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static void assertIntegrity(
      TableRecord record, String metadataLocation, TrustedMetadata expected) {
    Assertions.assertTrue(record.encrypted());
    Assertions.assertEquals(metadataLocation, record.integrityMetadataLocation());
    Assertions.assertEquals(expected.keyId(), record.keyId());
    Assertions.assertEquals(expected.metadataHash(), record.metadataHash());
  }

  private static JdbcTrustedMetadataStore newStore(JdbcUtil.SchemaVersion version) {
    return new JdbcTrustedMetadataStore(CATALOG, version);
  }

  /** The catalog under test on the real connection, as the REST server would construct it. */
  private JdbcCatalogWithMetadataLocationSupport newCatalog(
      Scope scope, boolean initializeCatalogTables, boolean governedEncryptionConfigured) {
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(
            initializeCatalogTables, governedEncryptionConfigured);
    catalog.initialize(CATALOG, catalogProperties(scope));
    return catalog;
  }

  private Map<String, String> catalogProperties(Scope scope) {
    return Map.of(
        CatalogProperties.URI,
        scope.url,
        JdbcCatalog.PROPERTY_PREFIX + "user",
        scope.user,
        JdbcCatalog.PROPERTY_PREFIX + "password",
        scope.password,
        JdbcUtil.SCHEMA_VERSION_PROPERTY,
        JdbcUtil.SchemaVersion.V1.name(),
        CatalogProperties.WAREHOUSE_LOCATION,
        warehouse.toUri().toString(),
        CatalogProperties.FILE_IO_IMPL,
        "org.apache.iceberg.inmemory.InMemoryFileIO");
  }

  private static BaseTable createEncryptedTable(JdbcCatalog catalog, TableIdentifier identifier) {
    return (BaseTable)
        catalog
            .buildTable(identifier, SCHEMA)
            .withProperty(TableProperties.FORMAT_VERSION, "3")
            .withProperty(TableProperties.ENCRYPTION_TABLE_KEY, "key-1")
            .create();
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private Scope newScope(String engine, String mySqlCreateSuffix) throws SQLException {
    JdbcDatabaseContainer<?> container =
        CONTAINERS.computeIfAbsent(engine, TestJdbcIntegrityContract::startContainer);
    String name = "scope_" + SCOPES.incrementAndGet();
    Scope scope;
    if (MYSQL.equals(engine)) {
      // Only root may create databases; the catalog user is scoped to the container's default one.
      try (Connection root = containerConnection(container, MYSQL_ROOT_USER)) {
        execute(root, "CREATE DATABASE " + name + mySqlCreateSuffix);
      }
      String url = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + name);
      scope = new Scope(engine, name, url, MYSQL_ROOT_USER, container.getPassword());
    } else {
      try (Connection connection = containerConnection(container, container.getUsername())) {
        execute(connection, "CREATE SCHEMA " + name);
      }
      String url =
          container.getJdbcUrl()
              + (container.getJdbcUrl().contains("?") ? "&" : "?")
              + "currentSchema="
              + name;
      scope = new Scope(engine, name, url, container.getUsername(), container.getPassword());
    }
    scopes.add(scope);
    return scope;
  }

  private static void dropScope(Scope scope) throws SQLException {
    JdbcDatabaseContainer<?> container = CONTAINERS.get(scope.engine);
    if (MYSQL.equals(scope.engine)) {
      try (Connection root = containerConnection(container, MYSQL_ROOT_USER)) {
        execute(root, "DROP DATABASE " + scope.name);
      }
    } else {
      try (Connection connection = containerConnection(container, container.getUsername())) {
        execute(connection, "DROP SCHEMA " + scope.name + " CASCADE");
      }
    }
  }

  private static Connection containerConnection(JdbcDatabaseContainer<?> container, String user)
      throws SQLException {
    return DriverManager.getConnection(container.getJdbcUrl(), user, container.getPassword());
  }

  private static JdbcDatabaseContainer<?> startContainer(String engine) {
    JdbcDatabaseContainer<?> container =
        MYSQL.equals(engine)
            ? new MySQLContainer<>("mysql:8.0")
            : new PostgreSQLContainer<>("postgres:13");
    container.start();
    return container;
  }

  /** An isolated database scope: a schema on PostgreSQL, a database on MySQL. */
  private static final class Scope {
    private final String engine;
    private final String name;
    private final String url;
    private final String user;
    private final String password;

    private Scope(String engine, String name, String url, String user, String password) {
      this.engine = engine;
      this.name = name;
      this.url = url;
      this.user = user;
      this.password = password;
    }

    private Connection connect() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }
  }
}
