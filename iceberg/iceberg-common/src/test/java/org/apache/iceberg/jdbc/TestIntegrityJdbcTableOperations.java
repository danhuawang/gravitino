/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.utils.jdbc.JdbcSqlScriptUtils;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests the atomic JDBC pointer and trusted-metadata operations on H2. */
class TestIntegrityJdbcTableOperations {

  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
  private static final String KEY = "kms-key-1";
  private static final String OTHER_KEY = "kms-key-2";
  // H2 reports closed connections as 90007 rather than PostgreSQL/MySQL's 08003.

  @Test
  void testStrictModeCreateRequiresExistingNamespace() throws Exception {
    try (TestContext context = TestContext.create("strict_mode")) {
      Map<String, String> strictMode = ImmutableMap.of(JdbcUtil.STRICT_MODE_PROPERTY, "true");
      IntegrityJdbcTableOperations operations =
          context.operations(context.identifier, context.connections, strictMode);

      NoSuchNamespaceException missing =
          Assertions.assertThrows(
              NoSuchNamespaceException.class, () -> operations.commit(null, context.metadata(KEY)));
      Assertions.assertTrue(
          missing.getMessage().contains("Namespace ns does not exist"), missing.getMessage());
      Assertions.assertEquals(0, context.countRows(JdbcUtil.CATALOG_TABLE_VIEW_NAME));

      // A namespace that upstream created (a properties row) satisfies the check.
      context.execute(
          "INSERT INTO iceberg_namespace_properties VALUES ('"
              + context.catalogName
              + "', 'ns', 'exists', 'true')");
      operations.commit(null, context.metadata(KEY));
      context.assertPointerMatchesIntegrity(operations.refresh().metadataFileLocation());
    }
  }

  @Test
  void testCreateRejectsNameClashWithV1View() throws Exception {
    try (TestContext context = TestContext.create("view_clash")) {
      context.execute(
          "INSERT INTO iceberg_tables VALUES ('"
              + context.catalogName
              + "', 'ns', 'encrypted', 'memory://warehouse/view/metadata/v1.metadata.json',"
              + " NULL, 'VIEW')");

      AlreadyExistsException clash =
          Assertions.assertThrows(
              AlreadyExistsException.class,
              () -> context.operations(context.connections).commit(null, context.metadata(KEY)));

      Assertions.assertTrue(
          clash.getMessage().contains("View with same name already exists"), clash.getMessage());
      Assertions.assertEquals(1, context.countRows(JdbcUtil.CATALOG_TABLE_VIEW_NAME));
      Assertions.assertEquals(0, context.countRows(JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  @Test
  void testIntegrityInsertFailureRollsBackPointer() throws Exception {
    try (TestContext context = TestContext.create("create_rollback")) {
      // The pointer insert succeeds; the integrity insert then overflows table_key_id.
      String oversizedKey = "k".repeat(2049);

      Assertions.assertThrows(
          UncheckedSQLException.class,
          () ->
              context.operations(context.connections).commit(null, context.metadata(oversizedKey)));

      Assertions.assertEquals(0, context.countRows(JdbcUtil.CATALOG_TABLE_VIEW_NAME));
    }
  }

  @Test
  void testIntegrityUpdateFailureRollsBackPointer() throws Exception {
    try (TestContext context = TestContext.create("update_rollback")) {
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      operations.commit(null, context.metadata(KEY));
      TableMetadata created = operations.refresh();
      context.lockIntegrityLocation(created.metadataFileLocation());

      Assertions.assertThrows(
          UncheckedSQLException.class,
          () -> operations.commit(created, withProperty(created, "must-roll-back", "true")));

      context.assertPointerMatchesIntegrity(created.metadataFileLocation());
    }
  }

  @Test
  void testConcurrentPointerChangeIsCommitFailure() throws Exception {
    try (TestContext context = TestContext.create("concurrent_pointer")) {
      IntegrityJdbcTableOperations stale = context.operations(context.connections);
      stale.commit(null, context.metadata(KEY));
      TableMetadata staleBase = stale.refresh();
      IntegrityJdbcTableOperations winner = context.operations(context.connections);
      TableMetadata winnerBase = winner.refresh();
      winner.commit(winnerBase, withProperty(winnerBase, "writer", "winner"));

      Assertions.assertThrows(
          CommitFailedException.class,
          () -> stale.commit(staleBase, withProperty(staleBase, "writer", "stale")));
      Assertions.assertEquals("winner", winner.refresh().properties().get("writer"));
    }
  }

  @Test
  void testCommitRejectsKeyChangeAndPlainMetadataWithIntegrityRow() throws Exception {
    try (TestContext context = TestContext.create("key_change")) {
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      operations.commit(null, context.metadata(KEY));
      TableMetadata created = operations.refresh();

      Assertions.assertThrows(
          ValidationException.class,
          () ->
              operations.commit(
                  created, withProperty(created, TableProperties.ENCRYPTION_TABLE_KEY, OTHER_KEY)));

      // A plain metadata file behind a pointer that has an integrity row is never accepted as base.
      TableIdentifier plainTable = context.identifier("plain_table");
      IntegrityJdbcTableOperations plain = context.operations(plainTable, context.connections);
      plain.commit(null, context.metadata(null));
      TableMetadata plainBase = plain.refresh();
      context.execute(
          "INSERT INTO iceberg_metadata_integrity VALUES ('"
              + context.catalogName
              + "', 'ns', 'plain_table', '"
              + plainBase.metadataFileLocation()
              + "', 'kms-key-1', 'SHA-256', '"
              + Base64.getEncoder().encodeToString(new byte[32])
              + "')");
      Assertions.assertThrows(
          MetadataIntegrityException.class,
          () -> plain.commit(plainBase, withProperty(plainBase, "revision", "2")));
      Assertions.assertEquals(
          plainBase.metadataFileLocation(), context.pointerLocation(plainTable));
    }
  }

  @Test
  void testRefreshRejectsTamperedHashAndForeignIntegrityLocation() throws Exception {
    try (TestContext context = TestContext.create("tampered")) {
      context.operations(context.connections).commit(null, context.metadata(KEY));
      context.setIntegrityColumn(
          JdbcTrustedMetadataStore.METADATA_HASH_COLUMN,
          Base64.getEncoder().encodeToString(new byte[32]));
      MetadataIntegrityException badHash =
          Assertions.assertThrows(
              MetadataIntegrityException.class,
              () -> context.operations(context.connections).refresh());
      Assertions.assertTrue(badHash.getMessage().contains("verification failed"));

      // A row written for another location never vouches for the pointer's file; the file is not
      // even read.
      context.setIntegrityColumn(
          JdbcTrustedMetadataStore.METADATA_LOCATION_COLUMN, "s3://elsewhere");
      MetadataIntegrityException foreignRow =
          Assertions.assertThrows(
              MetadataIntegrityException.class,
              () -> context.operations(context.connections).refresh());
      Assertions.assertTrue(foreignRow.getMessage().contains("pointer does not match"));
    }
  }

  @Test
  void testRefreshRevalidatesWhenPointerIsUnchanged() throws Exception {
    try (TestContext context = TestContext.create("revalidate")) {
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      operations.commit(null, context.metadata(KEY));
      operations.refresh();

      context.setIntegrityColumn(JdbcTrustedMetadataStore.TABLE_KEY_ID_COLUMN, OTHER_KEY);
      MetadataIntegrityException changedKey =
          Assertions.assertThrows(MetadataIntegrityException.class, operations::refresh);
      Assertions.assertTrue(changedKey.getMessage().contains("Trusted table key does not match"));
      Assertions.assertThrows(MetadataIntegrityException.class, operations::current);

      // Without a row the unchanged pointer loads like native Hive without a hash.
      context.execute("DELETE FROM iceberg_metadata_integrity");
      TableMetadata loaded = operations.refresh();
      Assertions.assertEquals(KEY, loaded.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    }
  }

  @Test
  void testTableWithoutIntegrityRowLoads() throws Exception {
    try (TestContext context = TestContext.create("missing_row_load")) {
      context.operations(context.connections).commit(null, context.metadata(KEY));
      String location = context.pointerLocation(context.identifier);
      context.execute("DELETE FROM iceberg_metadata_integrity");
      IntegrityJdbcTableOperations operations = context.operations(context.connections);

      TableMetadata loaded = operations.refresh();

      Assertions.assertEquals(location, loaded.metadataFileLocation());
      Assertions.assertEquals(location, operations.refresh().metadataFileLocation());
      Assertions.assertEquals(location, operations.current().metadataFileLocation());
      Assertions.assertEquals(0, context.countRows(JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  @Test
  void testFirstSameKeyCommitEnrollsTableWithoutIntegrityRow() throws Exception {
    try (TestContext context = TestContext.create("missing_row_commit")) {
      context.operations(context.connections).commit(null, context.metadata(KEY));
      context.execute("DELETE FROM iceberg_metadata_integrity");
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      TableMetadata base = operations.refresh();

      operations.commit(base, withProperty(base, "revision", "2"));

      TableMetadata committed = operations.refresh();
      Assertions.assertNotEquals(base.metadataFileLocation(), committed.metadataFileLocation());
      context.assertPointerMatchesIntegrity(committed.metadataFileLocation());
      Assertions.assertEquals(
          KEY, context.integrityColumn(JdbcTrustedMetadataStore.TABLE_KEY_ID_COLUMN));
      Assertions.assertEquals(
          IcebergMetadataIntegrity.metadataHash(committed),
          context.integrityColumn(JdbcTrustedMetadataStore.METADATA_HASH_COLUMN));

      // From now on every load verifies against the inserted row.
      Assertions.assertEquals(
          committed.metadataFileLocation(),
          context.operations(context.connections).refresh().metadataFileLocation());
      context.setIntegrityColumn(
          JdbcTrustedMetadataStore.METADATA_HASH_COLUMN,
          Base64.getEncoder().encodeToString(new byte[32]));
      MetadataIntegrityException tampered =
          Assertions.assertThrows(
              MetadataIntegrityException.class,
              () -> context.operations(context.connections).refresh());
      Assertions.assertTrue(tampered.getMessage().contains("verification failed"));
    }
  }

  @Test
  void testCommitOnTableWithoutIntegrityRowRejectsKeyTransitions() throws Exception {
    try (TestContext context = TestContext.create("missing_row_key_change")) {
      context.operations(context.connections).commit(null, context.metadata(KEY));
      context.execute("DELETE FROM iceberg_metadata_integrity");
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      TableMetadata base = operations.refresh();

      Assertions.assertThrows(
          ValidationException.class,
          () ->
              operations.commit(
                  base, withProperty(base, TableProperties.ENCRYPTION_TABLE_KEY, OTHER_KEY)));
      Assertions.assertThrows(
          ValidationException.class,
          () ->
              operations.commit(
                  base,
                  TableMetadata.buildFrom(base)
                      .removeProperties(Set.of(TableProperties.ENCRYPTION_TABLE_KEY))
                      .build()));
      Assertions.assertEquals(
          base.metadataFileLocation(), context.pointerLocation(context.identifier));

      // A plain table has no row either; a commit cannot add a key to it.
      TableIdentifier plainTable = context.identifier("plain_table");
      IntegrityJdbcTableOperations plain = context.operations(plainTable, context.connections);
      plain.commit(null, context.metadata(null));
      TableMetadata plainBase = plain.refresh();
      Assertions.assertThrows(
          ValidationException.class,
          () ->
              plain.commit(
                  plainBase, withProperty(plainBase, TableProperties.ENCRYPTION_TABLE_KEY, KEY)));
      Assertions.assertEquals(
          plainBase.metadataFileLocation(), context.pointerLocation(plainTable));
      Assertions.assertEquals(0, context.countRows(JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  @Test
  void testOverwriteRefusesTableWithoutIntegrityRow() throws Exception {
    try (TestContext context = TestContext.create("missing_row_overwrite")) {
      context.operations(context.connections).commit(null, context.metadata(KEY));
      context.execute("DELETE FROM iceberg_metadata_integrity");
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      TableMetadata base = operations.refresh();
      // Same key: only the missing row can refuse it, not the key guard.
      TableMetadata target = withProperty(base, "registered", "again");

      MetadataIntegrityException refused =
          Assertions.assertThrows(
              MetadataIntegrityException.class,
              () -> operations.overwrite(base, context.fromFile(target, "missing_row_target")));

      Assertions.assertTrue(refused.getMessage().contains("is not enrolled"), refused.getMessage());
      Assertions.assertEquals(
          base.metadataFileLocation(), context.pointerLocation(context.identifier));
      Assertions.assertEquals(0, context.countRows(JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  @Test
  void testIntegrityRowForAnotherLocationStillRefusesLoadAndCommit() throws Exception {
    try (TestContext context = TestContext.create("stale_row")) {
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      operations.commit(null, context.metadata(KEY));
      TableMetadata base = operations.refresh();
      context.setIntegrityColumn(
          JdbcTrustedMetadataStore.METADATA_LOCATION_COLUMN, "memory://elsewhere");

      MetadataIntegrityException load =
          Assertions.assertThrows(
              MetadataIntegrityException.class,
              () -> context.operations(context.connections).refresh());
      Assertions.assertTrue(load.getMessage().contains("pointer does not match"));
      MetadataIntegrityException commit =
          Assertions.assertThrows(
              MetadataIntegrityException.class,
              () -> operations.commit(base, withProperty(base, "revision", "2")));
      Assertions.assertTrue(commit.getMessage().contains("pointer does not match"));
      Assertions.assertEquals(
          base.metadataFileLocation(), context.pointerLocation(context.identifier));
      Assertions.assertEquals(
          "memory://elsewhere",
          context.integrityColumn(JdbcTrustedMetadataStore.METADATA_LOCATION_COLUMN));
    }
  }

  @Test
  void testRejectedNewLocationIsNotPublishedToCache() throws Exception {
    try (TestContext context = TestContext.create("reject_new_location")) {
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      operations.commit(null, context.metadata(KEY));
      String originalLocation = operations.refresh().metadataFileLocation();
      IntegrityJdbcTableOperations writer = context.operations(context.connections);
      TableMetadata writerBase = writer.refresh();
      writer.commit(writerBase, withProperty(writerBase, "revision", "2"));
      String newLocation = writer.refresh().metadataFileLocation();
      context.setIntegrityColumn(JdbcTrustedMetadataStore.TABLE_KEY_ID_COLUMN, OTHER_KEY);

      Assertions.assertThrows(MetadataIntegrityException.class, operations::refresh);
      Assertions.assertEquals(originalLocation, operations.currentMetadataLocation());
      Assertions.assertThrows(MetadataIntegrityException.class, operations::current);
      Assertions.assertEquals(originalLocation, operations.currentMetadataLocation());

      context.setIntegrityColumn(JdbcTrustedMetadataStore.TABLE_KEY_ID_COLUMN, KEY);
      Assertions.assertEquals(newLocation, operations.current().metadataFileLocation());
    }
  }

  @Test
  void testOverwriteRejectsKeyChangeAndStaleBase() throws Exception {
    try (TestContext context = TestContext.create("overwrite_rejects")) {
      IntegrityJdbcTableOperations operations = context.operations(context.connections);
      operations.commit(null, context.metadata(KEY));
      TableMetadata base = operations.refresh();
      String baseLocation = base.metadataFileLocation();

      TableMetadata rekeyed = withProperty(base, TableProperties.ENCRYPTION_TABLE_KEY, OTHER_KEY);
      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> operations.overwrite(base, context.fromFile(rekeyed, "rekeyed")));
      TableMetadata stripped =
          TableMetadata.buildFrom(base)
              .removeProperties(Set.of(TableProperties.ENCRYPTION_TABLE_KEY))
              .build();
      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> operations.overwrite(base, context.fromFile(stripped, "stripped")));
      context.assertPointerMatchesIntegrity(baseLocation);

      IntegrityJdbcTableOperations writer = context.operations(context.connections);
      TableMetadata writerBase = writer.refresh();
      writer.commit(writerBase, withProperty(writerBase, "revision", "2"));
      TableMetadata target = withProperty(base, "registered", "again");
      Assertions.assertThrows(
          CommitFailedException.class,
          () -> operations.overwrite(base, context.fromFile(target, "stale")));
      context.assertPointerMatchesIntegrity(writer.refresh().metadataFileLocation());
    }
  }

  @Test
  void testFailureAfterCommitAttemptReportsUnknownWithoutRetry() throws Exception {
    AtomicInteger commitCalls = new AtomicInteger();
    try (TestContext context = TestContext.create("unknown_commit");
        FaultyJdbcClientPool connections =
            new FaultyJdbcClientPool(
                context.uri,
                ImmutableMap.of(),
                (delegate, method, arguments) -> {
                  if ("commit".equals(method)) {
                    commitCalls.incrementAndGet();
                    delegate.commit();
                    throw new SQLException("Commit acknowledgement was lost", "HY000");
                  }
                  return null;
                })) {

      Assertions.assertThrows(
          CommitStateUnknownException.class,
          () -> context.operations(connections).commit(null, context.metadata(KEY)));

      Assertions.assertEquals(1, commitCalls.get());
      Assertions.assertEquals(1, context.countRows(JdbcUtil.CATALOG_TABLE_VIEW_NAME));
      Assertions.assertEquals(1, context.countRows(JdbcTrustedMetadataStore.TABLE_NAME));
    }
  }

  private static TableMetadata withProperty(TableMetadata base, String key, String value) {
    return TableMetadata.buildFrom(base).setProperties(ImmutableMap.of(key, value)).build();
  }

  /**
   * One H2 database with Iceberg's V1 catalog and namespace tables and the integrity table, and one
   * table.
   */
  private static final class TestContext implements AutoCloseable {
    private final String uri;
    private final String catalogName;
    private final TableIdentifier identifier;
    private final InMemoryFileIO fileIO = new InMemoryFileIO();
    private final JdbcClientPool connections;

    private TestContext(String prefix) {
      this.uri =
          String.format(
              "jdbc:h2:mem:%s_%s;DB_CLOSE_DELAY=-1",
              prefix, UUID.randomUUID().toString().replace("-", ""));
      this.catalogName = "jdbc_" + prefix;
      this.identifier = identifier("encrypted");
      this.connections = new JdbcClientPool(uri, ImmutableMap.of());
    }

    private static TestContext create(String prefix) throws Exception {
      TestContext context = new TestContext(prefix);
      context.execute(JdbcUtil.V0_CREATE_CATALOG_SQL);
      context.execute(JdbcUtil.V1_UPDATE_CATALOG_SQL);
      context.execute(JdbcUtil.CREATE_NAMESPACE_PROPERTIES_TABLE_SQL);
      String resource =
          "/org/apache/gravitino/iceberg/jdbc/metadata-integrity/postgresql/schema-v1.sql";
      try (InputStream input =
              TestIntegrityJdbcTableOperations.class.getResourceAsStream(resource);
          Connection connection = DriverManager.getConnection(context.uri)) {
        Assertions.assertNotNull(input, "Missing schema resource " + resource);
        JdbcSqlScriptUtils.executeSqlScript(
            connection, new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
      return context;
    }

    private TableIdentifier identifier(String name) {
      return TableIdentifier.of("ns", name);
    }

    private IntegrityJdbcTableOperations operations(JdbcClientPool clientPool) {
      return operations(identifier, clientPool);
    }

    private IntegrityJdbcTableOperations operations(
        TableIdentifier table, JdbcClientPool clientPool) {
      return operations(table, clientPool, ImmutableMap.of());
    }

    private IntegrityJdbcTableOperations operations(
        TableIdentifier table, JdbcClientPool clientPool, Map<String, String> catalogProperties) {
      return new IntegrityJdbcTableOperations(
          clientPool,
          fileIO,
          catalogName,
          table,
          catalogProperties,
          JdbcUtil.SchemaVersion.V1,
          new JdbcTrustedMetadataStore(catalogName, JdbcUtil.SchemaVersion.V1));
    }

    private TableMetadata metadata(String keyId) {
      ImmutableMap.Builder<String, String> properties =
          ImmutableMap.<String, String>builder().put(TableProperties.FORMAT_VERSION, "3");
      if (keyId != null) {
        properties.put(TableProperties.ENCRYPTION_TABLE_KEY, keyId);
      }
      return TableMetadata.newTableMetadata(
          SCHEMA, PartitionSpec.unpartitioned(), "memory://warehouse/table", properties.build());
    }

    private String write(TableMetadata metadata, String name) {
      String location = "memory://warehouse/table/metadata/" + name + ".metadata.json";
      TableMetadataParser.write(metadata, fileIO.newOutputFile(location));
      return location;
    }

    private TableMetadata fromFile(TableMetadata metadata, String name) {
      return TableMetadataParser.read(fileIO, write(metadata, name));
    }

    private void assertPointerMatchesIntegrity(String expectedLocation) throws Exception {
      Assertions.assertEquals(expectedLocation, pointerLocation(identifier));
      Assertions.assertEquals(
          expectedLocation, integrityColumn(JdbcTrustedMetadataStore.METADATA_LOCATION_COLUMN));
    }

    private String pointerLocation(TableIdentifier table) throws Exception {
      return queryOne(
          "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = '"
              + catalogName
              + "' AND table_namespace = 'ns' AND table_name = '"
              + table.name()
              + "'");
    }

    private String integrityColumn(String column) throws Exception {
      return queryOne("SELECT " + column + " FROM iceberg_metadata_integrity");
    }

    private int countRows(String tableName) throws Exception {
      return Integer.parseInt(queryOne("SELECT COUNT(*) FROM " + tableName));
    }

    private void setIntegrityColumn(String column, String value) throws Exception {
      try (Connection connection = DriverManager.getConnection(uri);
          PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE iceberg_metadata_integrity SET " + column + " = ?")) {
        statement.setString(1, value);
        Assertions.assertEquals(1, statement.executeUpdate());
      }
    }

    private void lockIntegrityLocation(String metadataLocation) throws Exception {
      execute(
          "ALTER TABLE iceberg_metadata_integrity ADD CONSTRAINT keep_location"
              + " CHECK (metadata_location = '"
              + metadataLocation
              + "')");
    }

    private void execute(String sql) throws Exception {
      try (Connection connection = DriverManager.getConnection(uri);
          Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }

    private String queryOne(String sql) throws Exception {
      try (Connection connection = DriverManager.getConnection(uri);
          Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(sql)) {
        Assertions.assertTrue(resultSet.next(), sql);
        String value = resultSet.getString(1);
        Assertions.assertFalse(resultSet.next(), sql);
        return value;
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
      connections.close();
    }
  }

  /** A single-connection pool whose connection answers selected calls with an injected fault. */
  private static final class FaultyJdbcClientPool extends JdbcClientPool {
    private final AtomicInteger newClientCalls = new AtomicInteger();
    private final ConnectionFault fault;

    private FaultyJdbcClientPool(
        String uri, Map<String, String> properties, ConnectionFault fault) {
      super(1, uri, properties);
      this.fault = fault;
    }

    /** {@inheritDoc} */
    @Override
    protected Connection newClient() {
      newClientCalls.incrementAndGet();
      Connection delegate = super.newClient();
      return (Connection)
          Proxy.newProxyInstance(
              TestIntegrityJdbcTableOperations.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                try {
                  Object faultResult = fault.invoke(delegate, method.getName(), arguments);
                  return faultResult != null ? faultResult : method.invoke(delegate, arguments);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }
  }

  /** Returns a value to answer the call with, or {@code null} to pass it through. */
  @FunctionalInterface
  private interface ConnectionFault {
    Object invoke(Connection delegate, String methodName, Object[] arguments) throws Throwable;
  }
}
