/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.iceberg.jdbc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import com.google.common.collect.ImmutableMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * H2 tests of the catalog's initialization, capability and wiring. The cascade and pre-read gate
 * cases of rename and drop run on real PostgreSQL and MySQL in {@code TestJdbcIntegrityContract}.
 */
public class TestJdbcCatalogWithMetadataLocationSupport {

  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));

  @Test
  void testLoadFields() {
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(true);
    Assertions.assertDoesNotThrow(
        () ->
            catalog.initialize(
                "jdbc",
                ImmutableMap.of(
                    CatalogProperties.URI,
                    h2Uri("load_fields"),
                    CatalogProperties.WAREHOUSE_LOCATION,
                    "warehouse")));
  }

  @Test
  void testGovernedCatalogFailsOnceAndClosesWhenProvisionFails() throws Exception {
    // H2 is not a product the packaged DDL exists for: provisioning fails on the real connection.
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(true, true);
    Map<String, String> properties = catalogProperties(h2Uri("governed_provision_failure"), "V1");

    UncheckedSQLException exception =
        Assertions.assertThrows(
            UncheckedSQLException.class,
            () -> catalog.initialize("jdbc_governed_provision_failure", properties));

    Assertions.assertTrue(exception.getMessage().contains(JdbcTrustedMetadataStore.TABLE_NAME));
    Assertions.assertTrue(exception.getCause().getMessage().contains("H2"));
    Assertions.assertFalse(catalog.supportsAtomicMetadataIntegrity());
    Assertions.assertTrue(clientPool(catalog).isClosed());
  }

  @Test
  void testNonGovernedCatalogRunsInPlainUpstreamModeWhenProbeFails() throws Exception {
    String uri = h2Uri("plain_upstream_mode");
    String catalogName = "jdbc_plain_upstream_mode";
    Namespace namespace = Namespace.of("ns");
    TableIdentifier existing = TableIdentifier.of(namespace, "existing");
    TableIdentifier renamed = TableIdentifier.of(namespace, "renamed");
    // V0: upstream's V1 column probe misses H2's upper-cased schema when it does not initialize.
    JdbcCatalogWithMetadataLocationSupport initialized =
        initializedCatalog(uri, catalogName, true, "V0");
    initialized.createNamespace(namespace);
    initialized.createTable(existing, SCHEMA);
    initialized.close();
    dropIntegrityTable(uri);

    JdbcCatalogWithMetadataLocationSupport catalog =
        initializedCatalog(uri, catalogName, false, "V0");

    Assertions.assertFalse(catalog.supportsAtomicMetadataIntegrity());
    Assertions.assertFalse(catalog.newTableOps(existing) instanceof IntegrityJdbcTableOperations);
    BaseTable table = (BaseTable) catalog.loadTable(existing);
    TableMetadata firstMetadata = table.operations().current();
    table.updateProperties().set("revision", "2").commit();
    TableMetadata secondMetadata = table.operations().current();
    catalog.registerTable(existing, firstMetadata, true);
    Assertions.assertEquals(
        firstMetadata.metadataFileLocation(), catalog.metadataLocation(existing));
    catalog.registerTable(existing, secondMetadata, true);
    Assertions.assertEquals(
        secondMetadata.metadataFileLocation(), catalog.metadataLocation(existing));
    catalog.renameTable(existing, renamed);
    Assertions.assertTrue(catalog.tableExists(renamed));
    Assertions.assertTrue(catalog.dropTable(renamed, false));
    catalog.close();
  }

  @Test
  void testIntegrityAwareCatalogReportsCapabilityAfterProvisionAndProbe() throws Exception {
    String uri = h2Uri("capability");
    String catalogName = "jdbc_capability";
    Namespace namespace = Namespace.of("ns");
    TableIdentifier identifier = TableIdentifier.of(namespace, "encrypted");
    // V0: upstream's V1 column probe misses H2's upper-cased schema when it does not initialize.
    JdbcCatalogWithMetadataLocationSupport provisioning =
        initializedCatalog(uri, catalogName, true, "V0");
    Assertions.assertTrue(provisioning.supportsAtomicMetadataIntegrity());
    provisioning.createNamespace(namespace);
    provisioning.close();

    JdbcCatalogWithMetadataLocationSupport probing =
        initializedCatalog(uri, catalogName, false, "V0");

    Assertions.assertTrue(probing.supportsAtomicMetadataIntegrity());
    Assertions.assertInstanceOf(
        IntegrityJdbcTableOperations.class, probing.newTableOps(identifier));
    BaseTable table = (BaseTable) createEncryptedTable(probing, identifier);
    Assertions.assertEquals(
        table.operations().current().metadataFileLocation(),
        loadIntegrityRow(uri, catalogName, identifier).metadataLocation);
    probing.close();
  }

  @Test
  void testRawIcebergInitializePropertyOverridesConstructorDefault() throws Exception {
    String uri = h2Uri("raw_init_override");
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(
            false,
            false,
            properties -> new DatabaseProductJdbcClientPool(properties, "PostgreSQL"));
    Map<String, String> properties = catalogProperties(uri, "V0");
    properties.put(JdbcUtil.INIT_CATALOG_TABLES_PROPERTY, "true");

    catalog.initialize("jdbc_raw_init_override", properties);

    Assertions.assertTrue(catalog.supportsAtomicMetadataIntegrity());
    try (Connection connection = DriverManager.getConnection(uri);
        Statement statement = connection.createStatement()) {
      Assertions.assertDoesNotThrow(
          () ->
              statement.executeQuery(
                  "SELECT metadata_hash FROM "
                      + JdbcTrustedMetadataStore.TABLE_NAME
                      + " WHERE 1 = 0"));
    }
    catalog.close();
  }

  @Test
  void testRawIcebergDisablePropertyOverridesConstructorDefault() throws Exception {
    String uri = h2Uri("raw_disable_override");
    String catalogName = "jdbc_raw_disable_override";
    initializedCatalog(uri, catalogName, true, "V0").close();
    dropIntegrityTable(uri);

    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(true);
    Map<String, String> properties = catalogProperties(uri, "V0");
    properties.put(JdbcUtil.INIT_CATALOG_TABLES_PROPERTY, "false");

    Assertions.assertDoesNotThrow(() -> catalog.initialize(catalogName, properties));
    Assertions.assertFalse(catalog.supportsAtomicMetadataIntegrity());
    try (Connection connection = DriverManager.getConnection(uri);
        Statement statement = connection.createStatement()) {
      Assertions.assertThrows(
          SQLException.class,
          () -> statement.executeQuery("SELECT * FROM " + JdbcTrustedMetadataStore.TABLE_NAME));
    }
    catalog.close();
  }

  @ParameterizedTest
  @CsvSource({
    "V0, false, JDBC catalog with V0 schema version should not support views",
    "V1, true, JDBC catalog with V1 schema version should support views"
  })
  void testSupportsViewsWithSchemaVersion(
      String schemaVersion, boolean expectedSupportsViews, String message) {
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(true);
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, "jdbc:sqlite::memory:");
    properties.put(CatalogProperties.WAREHOUSE_LOCATION, "warehouse");
    properties.put("jdbc.schema-version", schemaVersion);

    catalog.initialize("test_jdbc_" + schemaVersion.toLowerCase(), properties);

    Assertions.assertEquals(
        expectedSupportsViews, catalog.supportsViewsWithSchemaVersion(), message);
  }

  @Test
  void testMetadataLocationReadsPointerWithoutVerification() throws Exception {
    String uri = h2Uri("metadata_location");
    String catalogName = "jdbc_metadata_location";
    JdbcCatalogWithMetadataLocationSupport catalog = initializedCatalog(uri, catalogName);
    Namespace namespace = Namespace.of("ns");
    TableIdentifier identifier = TableIdentifier.of(namespace, "encrypted");
    catalog.createNamespace(namespace);
    Table table = createEncryptedTable(catalog, identifier);
    table.updateProperties().set("revision", "2").commit();
    String pointer = ((BaseTable) table).operations().current().metadataFileLocation();
    Assertions.assertEquals(pointer, catalog.metadataLocation(identifier));

    // The cache's location gate reads the pointer only; the verifying load still fails closed.
    setIntegrityColumn(
        uri, catalogName, identifier, JdbcTrustedMetadataStore.HASH_ALGORITHM_COLUMN, "SHA-512");
    Assertions.assertEquals(pointer, catalog.metadataLocation(identifier));
    MetadataIntegrityException algorithm =
        Assertions.assertThrows(
            MetadataIntegrityException.class, () -> catalog.loadTable(identifier));
    Assertions.assertTrue(
        algorithm.getMessage().contains("Unsupported trusted metadata hash algorithm"));

    setIntegrityColumn(
        uri,
        catalogName,
        identifier,
        JdbcTrustedMetadataStore.HASH_ALGORITHM_COLUMN,
        IcebergMetadataIntegrity.HASH_ALGORITHM);
    setIntegrityColumn(
        uri,
        catalogName,
        identifier,
        JdbcTrustedMetadataStore.METADATA_HASH_COLUMN,
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    Assertions.assertEquals(pointer, catalog.metadataLocation(identifier));
    MetadataIntegrityException hash =
        Assertions.assertThrows(
            MetadataIntegrityException.class, () -> catalog.loadTable(identifier));
    Assertions.assertTrue(hash.getMessage().contains("integrity verification failed"));
    catalog.close();
  }

  @Test
  void testEncryptedRegistrationCreatesPointerAndIntegrityAtomically() throws Exception {
    String uri = h2Uri("register_encrypted");
    String catalogName = "jdbc_register";
    JdbcCatalogWithMetadataLocationSupport catalog = initializedCatalog(uri, catalogName);
    Namespace namespace = Namespace.of("ns");
    TableIdentifier identifier = TableIdentifier.of(namespace, "encrypted");
    catalog.createNamespace(namespace);
    Table table = createEncryptedTable(catalog, identifier);
    TableMetadata metadata = ((BaseTable) table).operations().current();
    String metadataLocation = metadata.metadataFileLocation();
    Assertions.assertTrue(catalog.dropTable(identifier, false));

    Table registered = catalog.registerTable(identifier, metadataLocation);

    IntegrityRow integrity = loadIntegrityRow(uri, catalogName, identifier);
    Assertions.assertEquals(metadataLocation, loadPointer(uri, catalogName, identifier));
    Assertions.assertEquals(metadataLocation, integrity.metadataLocation);
    Assertions.assertEquals("kms-key-1", integrity.keyId);
    Assertions.assertEquals(
        IcebergMetadataIntegrity.metadataHash(metadata), integrity.metadataHash);
    Assertions.assertEquals(
        metadataLocation, ((BaseTable) registered).operations().current().metadataFileLocation());
    catalog.close();
  }

  @Test
  void testRegisterTableOverwrite() throws Exception {
    JdbcCatalogWithMetadataLocationSupport catalog =
        initializedCatalog(h2Uri("register_overwrite"), "jdbc_overwrite");
    Namespace namespace = Namespace.of("ns");
    catalog.createNamespace(namespace);
    TableIdentifier ident = TableIdentifier.of(namespace, "t");

    BaseTable table = (BaseTable) catalog.createTable(ident, SCHEMA);
    TableMetadata metadataV1 = table.operations().current();

    table.updateProperties().set("version", "v2").commit();
    TableMetadata metadataV2 = table.operations().current();

    catalog.registerTable(ident, metadataV1, true);
    Assertions.assertEquals(metadataV1.metadataFileLocation(), catalog.metadataLocation(ident));
    Assertions.assertFalse(
        ((BaseTable) catalog.loadTable(ident)).properties().containsKey("version"));

    catalog.registerTable(ident, metadataV2, true);
    Assertions.assertEquals(metadataV2.metadataFileLocation(), catalog.metadataLocation(ident));
    Assertions.assertEquals(
        "v2", ((BaseTable) catalog.loadTable(ident)).properties().get("version"));

    Assertions.assertThrows(
        AlreadyExistsException.class, () -> catalog.registerTable(ident, metadataV2, false));
    catalog.close();
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void testParsedEncryptedRegistrationCreatesPointerAndIntegrityAtomically(boolean overwrite)
      throws Exception {
    String uri = h2Uri("register_encrypted");
    String catalogName = "jdbc_register";
    JdbcCatalogWithMetadataLocationSupport catalog = initializedCatalog(uri, catalogName);
    Namespace namespace = Namespace.of("ns");
    TableIdentifier identifier = TableIdentifier.of(namespace, "encrypted");
    catalog.createNamespace(namespace);
    Table table = createEncryptedTable(catalog, identifier);
    TableMetadata metadata = ((BaseTable) table).operations().current();
    String metadataLocation = metadata.metadataFileLocation();
    Assertions.assertTrue(catalog.dropTable(identifier, false));

    Table registered = catalog.registerTable(identifier, metadata, overwrite);

    IntegrityRow integrity = loadIntegrityRow(uri, catalogName, identifier);
    Assertions.assertEquals(metadataLocation, loadPointer(uri, catalogName, identifier));
    Assertions.assertEquals(metadataLocation, integrity.metadataLocation);
    Assertions.assertEquals("kms-key-1", integrity.keyId);
    Assertions.assertEquals(
        IcebergMetadataIntegrity.metadataHash(metadata), integrity.metadataHash);
    Assertions.assertEquals(
        metadataLocation, ((BaseTable) registered).operations().current().metadataFileLocation());
    catalog.close();
  }

  @Test
  void testEncryptedSameKeyOverwriteUpdatesBothRecordsAndRollsBackTogether() throws Exception {
    String uri = h2Uri("register_encrypted_overwrite");
    String catalogName = "jdbc_register_overwrite";
    JdbcCatalogWithMetadataLocationSupport catalog = initializedCatalog(uri, catalogName);
    Namespace namespace = Namespace.of("ns");
    TableIdentifier identifier = TableIdentifier.of(namespace, "encrypted");
    catalog.createNamespace(namespace);
    BaseTable table = (BaseTable) createEncryptedTable(catalog, identifier);
    TableMetadata firstMetadata = table.operations().current();
    String firstLocation = firstMetadata.metadataFileLocation();
    table.updateProperties().set("revision", "2").commit();
    TableMetadata secondMetadata = table.operations().current();

    catalog.registerTable(identifier, firstMetadata, true);

    IntegrityRow firstIntegrity = loadIntegrityRow(uri, catalogName, identifier);
    Assertions.assertEquals(firstLocation, loadPointer(uri, catalogName, identifier));
    Assertions.assertEquals(firstLocation, firstIntegrity.metadataLocation);
    Assertions.assertEquals(
        IcebergMetadataIntegrity.metadataHash(firstMetadata), firstIntegrity.metadataHash);

    addLocationConstraint(uri, firstLocation);
    Assertions.assertThrows(
        UncheckedSQLException.class, () -> catalog.registerTable(identifier, secondMetadata, true));
    Assertions.assertEquals(firstLocation, loadPointer(uri, catalogName, identifier));
    Assertions.assertEquals(
        firstLocation, loadIntegrityRow(uri, catalogName, identifier).metadataLocation);
    catalog.close();
  }

  @Test
  void testRegistrationPublishesParsedMetadataWithoutReadingItAgain() throws Exception {
    String uri = h2Uri("register_single_derive");
    String catalogName = "jdbc_register_single_derive";
    JdbcCatalogWithMetadataLocationSupport catalog = initializedCatalog(uri, catalogName);
    Namespace namespace = Namespace.of("ns");
    TableIdentifier identifier = TableIdentifier.of(namespace, "encrypted");
    catalog.createNamespace(namespace);
    BaseTable table = (BaseTable) createEncryptedTable(catalog, identifier);
    String location = table.operations().current().metadataFileLocation();
    Assertions.assertTrue(catalog.dropTable(identifier, false));

    TableMetadata target;
    try (MockedStatic<TableMetadataParser> parser =
            mockStatic(TableMetadataParser.class, CALLS_REAL_METHODS);
        MockedStatic<IcebergMetadataIntegrity> integrity =
            mockStatic(IcebergMetadataIntegrity.class, CALLS_REAL_METHODS)) {
      target = TableMetadataParser.read(table.io(), location);
      catalog.registerTable(identifier, target, false);

      parser.verify(() -> TableMetadataParser.read(any(FileIO.class), anyString()), times(1));
      integrity.verify(() -> IcebergMetadataIntegrity.derive(any()), times(1));
      integrity.verify(() -> IcebergMetadataIntegrity.metadataHash(any()), times(1));
    }

    Assertions.assertEquals(location, loadPointer(uri, catalogName, identifier));
    Assertions.assertEquals(
        IcebergMetadataIntegrity.metadataHash(target),
        loadIntegrityRow(uri, catalogName, identifier).metadataHash);
    catalog.close();
  }

  @Test
  void testRenameAndDropVerifyEncryptedTablesAndSkipPlainTableFiles() throws Exception {
    String uri = h2Uri("pre_read_gate");
    String catalogName = "jdbc_pre_read_gate";
    JdbcCatalogWithMetadataLocationSupport catalog = initializedCatalog(uri, catalogName);
    Namespace namespace = Namespace.of("ns");
    TableIdentifier plainIdentifier = TableIdentifier.of(namespace, "plain");
    TableIdentifier plainRenamed = TableIdentifier.of(namespace, "plain_renamed");
    TableIdentifier encryptedIdentifier = TableIdentifier.of(namespace, "encrypted");
    TableIdentifier encryptedRenamed = TableIdentifier.of(namespace, "encrypted_renamed");
    catalog.createNamespace(namespace);
    BaseTable plain = (BaseTable) catalog.createTable(plainIdentifier, SCHEMA);
    BaseTable encrypted = (BaseTable) createEncryptedTable(catalog, encryptedIdentifier);
    String encryptedLocation = encrypted.operations().current().metadataFileLocation();

    // A plain table is renamed and dropped without reading its metadata file.
    plain.io().deleteFile(plain.operations().current().metadataFileLocation());
    catalog.renameTable(plainIdentifier, plainRenamed);
    Assertions.assertTrue(catalog.dropTable(plainRenamed, false));

    // An encrypted table is verified first: a tampered row blocks both operations.
    setIntegrityColumn(
        uri,
        catalogName,
        encryptedIdentifier,
        JdbcTrustedMetadataStore.METADATA_HASH_COLUMN,
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () -> catalog.renameTable(encryptedIdentifier, encryptedRenamed));
    Assertions.assertThrows(
        MetadataIntegrityException.class, () -> catalog.dropTable(encryptedIdentifier, false));
    Assertions.assertEquals(1, countPointerRows(uri, catalogName, encryptedIdentifier));
    Assertions.assertEquals(1, countIntegrityRows(uri, catalogName, encryptedIdentifier));

    setIntegrityColumn(
        uri,
        catalogName,
        encryptedIdentifier,
        JdbcTrustedMetadataStore.METADATA_HASH_COLUMN,
        IcebergMetadataIntegrity.metadataHash(encrypted.operations().current()));
    catalog.renameTable(encryptedIdentifier, encryptedRenamed);
    Assertions.assertEquals(0, countIntegrityRows(uri, catalogName, encryptedIdentifier));
    Assertions.assertEquals(
        encryptedLocation, loadIntegrityRow(uri, catalogName, encryptedRenamed).metadataLocation);
    Assertions.assertTrue(catalog.dropTable(encryptedRenamed, false));
    Assertions.assertEquals(0, countIntegrityRows(uri, catalogName, encryptedRenamed));
    catalog.close();
  }

  @Test
  void testTableExistsAnswersFromPointerRowAndFallsBackToUpstream() throws Exception {
    String uri = h2Uri("table_exists");
    String catalogName = "jdbc_table_exists";
    Namespace namespace = Namespace.of("ns");
    TableIdentifier plain = TableIdentifier.of(namespace, "t");
    TableIdentifier snapshots = TableIdentifier.of("ns", "t", "snapshots");
    TableIdentifier missing = TableIdentifier.of(namespace, "missing");
    TableIdentifier missingSnapshots = TableIdentifier.of("ns", "missing", "snapshots");
    // V0: upstream's V1 column probe misses H2's upper-cased schema when it does not initialize.
    JdbcCatalogWithMetadataLocationSupport catalog =
        initializedCatalog(uri, catalogName, true, "V0");
    catalog.createNamespace(namespace);
    catalog.createTable(plain, SCHEMA);

    // Upstream parity on the same rows: metadata-table identifiers resolve through the base table.
    JdbcCatalog upstream = new JdbcCatalog();
    upstream.initialize(catalogName, catalogProperties(uri, "V0"));
    for (TableIdentifier identifier : List.of(plain, snapshots, missing, missingSnapshots)) {
      Assertions.assertEquals(
          upstream.tableExists(identifier), catalog.tableExists(identifier), identifier.toString());
    }
    Assertions.assertTrue(catalog.tableExists(snapshots));
    Assertions.assertFalse(catalog.tableExists(missing));
    upstream.close();
    catalog.close();

    // Neither the pointer-row answer nor the missing-table fallback reads a file.
    JdbcCatalogWithMetadataLocationSupport noFileReads =
        new JdbcCatalogWithMetadataLocationSupport(
            false,
            false,
            properties -> new DatabaseProductJdbcClientPool(properties, "PostgreSQL"));
    Map<String, String> properties = catalogProperties(uri, "V0");
    properties.put(CatalogProperties.FILE_IO_IMPL, NoFileAccessFileIO.class.getName());
    noFileReads.initialize(catalogName, properties);
    Assertions.assertTrue(noFileReads.supportsAtomicMetadataIntegrity());
    Assertions.assertTrue(noFileReads.tableExists(plain));
    Assertions.assertFalse(noFileReads.tableExists(missing));
    Assertions.assertFalse(noFileReads.tableExists(missingSnapshots));
    noFileReads.close();
  }

  private static JdbcCatalogWithMetadataLocationSupport initializedCatalog(
      String uri, String catalogName) throws Exception {
    return initializedCatalog(uri, catalogName, true, "V1");
  }

  /** A non-governed catalog whose connection reports PostgreSQL, so the packaged DDL runs on H2. */
  private static JdbcCatalogWithMetadataLocationSupport initializedCatalog(
      String uri, String catalogName, boolean initializeCatalogTables, String schemaVersion)
      throws Exception {
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(
            initializeCatalogTables,
            false,
            properties -> new DatabaseProductJdbcClientPool(properties, "PostgreSQL"));
    catalog.initialize(catalogName, catalogProperties(uri, schemaVersion));
    return catalog;
  }

  private static Map<String, String> catalogProperties(String uri, String schemaVersion)
      throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, uri);
    properties.put(
        CatalogProperties.WAREHOUSE_LOCATION,
        Files.createTempDirectory("jdbc-integrity").toString());
    properties.put("jdbc.schema-version", schemaVersion);
    return properties;
  }

  private static JdbcClientPool clientPool(JdbcCatalogWithMetadataLocationSupport catalog)
      throws Exception {
    return (JdbcClientPool) FieldUtils.readField(catalog, "jdbcConnections", true);
  }

  private static Table createEncryptedTable(
      JdbcCatalogWithMetadataLocationSupport catalog, TableIdentifier identifier) {
    return catalog
        .buildTable(identifier, SCHEMA)
        .withProperty(TableProperties.FORMAT_VERSION, "3")
        .withProperty(TableProperties.ENCRYPTION_TABLE_KEY, "kms-key-1")
        .create();
  }

  private static String h2Uri(String prefix) {
    return String.format(
        "jdbc:h2:mem:%s_%s;DB_CLOSE_DELAY=-1",
        prefix, UUID.randomUUID().toString().replace("-", ""));
  }

  private static IntegrityRow loadIntegrityRow(
      String uri, String catalogName, TableIdentifier identifier) throws Exception {
    String sql =
        "SELECT metadata_location, table_key_id, metadata_hash"
            + " FROM iceberg_metadata_integrity WHERE catalog_name = ?"
            + " AND table_namespace = ? AND table_name = ?";
    try (Connection connection = DriverManager.getConnection(uri);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bindIdentity(statement, catalogName, identifier);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertTrue(resultSet.next());
        return new IntegrityRow(
            resultSet.getString("metadata_location"),
            resultSet.getString("table_key_id"),
            resultSet.getString("metadata_hash"));
      }
    }
  }

  private static String loadPointer(String uri, String catalogName, TableIdentifier identifier)
      throws Exception {
    String sql =
        "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = ?"
            + " AND table_namespace = ? AND table_name = ?";
    try (Connection connection = DriverManager.getConnection(uri);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bindIdentity(statement, catalogName, identifier);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertTrue(resultSet.next());
        return resultSet.getString(1);
      }
    }
  }

  private static int countPointerRows(String uri, String catalogName, TableIdentifier identifier)
      throws Exception {
    return countRows(uri, JdbcUtil.CATALOG_TABLE_VIEW_NAME, catalogName, identifier);
  }

  private static int countIntegrityRows(String uri, String catalogName, TableIdentifier identifier)
      throws Exception {
    return countRows(uri, JdbcTrustedMetadataStore.TABLE_NAME, catalogName, identifier);
  }

  private static int countRows(
      String uri, String tableName, String catalogName, TableIdentifier identifier)
      throws Exception {
    String sql =
        String.format(
            "SELECT COUNT(*) FROM %s WHERE catalog_name = ?"
                + " AND table_namespace = ? AND table_name = ?",
            tableName);
    try (Connection connection = DriverManager.getConnection(uri);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bindIdentity(statement, catalogName, identifier);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static void setIntegrityColumn(
      String uri, String catalogName, TableIdentifier identifier, String column, String value)
      throws Exception {
    String sql =
        String.format(
            "UPDATE %s SET %s = ? WHERE catalog_name = ?"
                + " AND table_namespace = ? AND table_name = ?",
            JdbcTrustedMetadataStore.TABLE_NAME, column);
    try (Connection connection = DriverManager.getConnection(uri);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      statement.setString(2, catalogName);
      statement.setString(3, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(4, identifier.name());
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  private static void addLocationConstraint(String uri, String metadataLocation) throws Exception {
    String escapedLocation = metadataLocation.replace("'", "''");
    try (Connection connection = DriverManager.getConnection(uri);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE iceberg_metadata_integrity ADD CONSTRAINT keep_original_location"
              + " CHECK (metadata_location = '"
              + escapedLocation
              + "')");
    }
  }

  private static void dropIntegrityTable(String uri) throws Exception {
    try (Connection connection = DriverManager.getConnection(uri);
        Statement statement = connection.createStatement()) {
      statement.execute(String.format("DROP TABLE %s", JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  private static void bindIdentity(
      PreparedStatement statement, String catalogName, TableIdentifier identifier)
      throws Exception {
    statement.setString(1, catalogName);
    statement.setString(2, JdbcUtil.namespaceToString(identifier.namespace()));
    statement.setString(3, identifier.name());
  }

  private static final class IntegrityRow {
    private final String metadataLocation;
    private final String keyId;
    private final String metadataHash;

    private IntegrityRow(String metadataLocation, String keyId, String metadataHash) {
      this.metadataLocation = metadataLocation;
      this.keyId = keyId;
      this.metadataHash = metadataHash;
    }
  }

  /** Fails every file access, so a test can prove a code path never touches a file. */
  public static final class NoFileAccessFileIO implements FileIO {
    @Override
    public InputFile newInputFile(String path) {
      throw new IllegalStateException("Unexpected file read: " + path);
    }

    @Override
    public OutputFile newOutputFile(String path) {
      throw new IllegalStateException("Unexpected file write: " + path);
    }

    @Override
    public void deleteFile(String path) {
      throw new IllegalStateException("Unexpected file delete: " + path);
    }
  }

  private static final class DatabaseProductJdbcClientPool extends JdbcClientPool {
    private final String databaseProduct;

    private DatabaseProductJdbcClientPool(Map<String, String> properties, String databaseProduct) {
      super(properties.get(CatalogProperties.URI), properties);
      this.databaseProduct = databaseProduct;
    }

    @Override
    protected Connection newClient() {
      Connection delegate = super.newClient();
      return (Connection)
          Proxy.newProxyInstance(
              DatabaseProductJdbcClientPool.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                try {
                  if ("getMetaData".equals(method.getName())) {
                    DatabaseMetaData metadata = delegate.getMetaData();
                    return Proxy.newProxyInstance(
                        DatabaseProductJdbcClientPool.class.getClassLoader(),
                        new Class<?>[] {DatabaseMetaData.class},
                        (metadataProxy, metadataMethod, metadataArguments) -> {
                          try {
                            if ("getDatabaseProductName".equals(metadataMethod.getName())) {
                              return databaseProduct;
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
  }

  @Test
  void testFailedSchemaMigrationClosesPartiallyCreatedResources() throws Exception {
    FileIO fileIO = Mockito.mock(FileIO.class);
    Connection connection = Mockito.mock(Connection.class);
    DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
    ResultSet columns = Mockito.mock(ResultSet.class);
    PreparedStatement alterTable = Mockito.mock(PreparedStatement.class);
    Mockito.when(connection.getMetaData()).thenReturn(metadata);
    Mockito.when(metadata.getColumns(null, null, "iceberg_tables", "iceberg_type"))
        .thenReturn(columns);
    Mockito.when(columns.next()).thenReturn(false);
    Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(alterTable);
    Mockito.when(alterTable.execute())
        .thenThrow(new SQLSyntaxErrorException("Duplicate column name 'iceberg_type'"));

    JdbcClientPool clientPool = new TestJdbcClientPool(connection);
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(
            properties -> fileIO, properties -> clientPool, false);
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, "jdbc:test");
    properties.put(CatalogProperties.WAREHOUSE_LOCATION, "warehouse");
    properties.put("jdbc.schema-version", "V1");

    Assertions.assertThrows(
        UncheckedSQLException.class, () -> catalog.initialize("test", properties));

    Assertions.assertTrue(clientPool.isClosed());
    Mockito.verify(connection).close();
    Mockito.verify(fileIO).close();
  }

  private static class TestJdbcClientPool extends JdbcClientPool {
    private final Connection connection;

    private TestJdbcClientPool(Connection connection) {
      super(1, "jdbc:test", Collections.emptyMap());
      this.connection = connection;
    }

    /** {@inheritDoc} */
    @Override
    protected Connection newClient() {
      return connection;
    }
  }
}
