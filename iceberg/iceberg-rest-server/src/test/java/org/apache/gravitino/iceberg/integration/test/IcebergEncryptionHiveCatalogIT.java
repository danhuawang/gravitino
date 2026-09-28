/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.integration.test;

import static org.apache.gravitino.iceberg.service.IcebergRESTUtils.NAMESPACE_SEPARATOR_URLENCODED_UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.cache.TableMetadataCache;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.HiveContainer;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog.TableBuilder;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

/**
 * Encrypted-metadata integrity coverage for the Iceberg REST service on a Hive metastore: the HMS
 * tuple (pointer, previous pointer, key, hash) through the table lifecycle, the native verification
 * failures and the server cache around them.
 *
 * <p>Embedded mode only: the catalog registers an in-JVM KMS client through {@code
 * getCatalogConfig}, which a packaged server on its own classpath cannot load.
 */
@Tag("gravitino-docker-test")
@TestInstance(Lifecycle.PER_CLASS)
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
public class IcebergEncryptionHiveCatalogIT extends IcebergRESTServiceBaseIT {
  private static final String ENCRYPTION_KEY_ID = "kms-key-1";
  private static final String SECOND_ENCRYPTION_KEY_ID = "kms-key-2";
  // Valid Base64 of the right length that cannot be the hash of any real metadata file.
  private static final String WRONG_METADATA_HASH =
      Base64.getEncoder().encodeToString(new byte[32]);
  private static final String MALFORMED_METADATA_HASH = "%%%";
  private static final String METADATA_HASH = BaseMetastoreTableOperations.METADATA_HASH_PROP;
  private static final String METADATA_LOCATION =
      BaseMetastoreTableOperations.METADATA_LOCATION_PROP;
  private static final Schema RECORD_SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id"));
  protected static final ContainerSuite containerSuite = ContainerSuite.getInstance();

  private final String testNamespace =
      GravitinoITUtils.genRandomName("iceberg_rest_encryption_hive");

  /** Binds the suite to the Hive catalog backend, like its plain Hive sibling. */
  public IcebergEncryptionHiveCatalogIT() {
    catalogType = IcebergCatalogBackend.HIVE;
  }

  @BeforeAll
  void prepareEncryptionNamespace() throws Exception {
    try (RESTCatalog catalog = newRestCatalog()) {
      catalog.createNamespace(Namespace.of(testNamespace));
    }
  }

  @AfterAll
  @Override
  void stopIcebergTestEnv() throws Exception {
    try {
      HiveMetaStoreClient hmsClient = createHmsClient();
      try {
        hmsClient.dropDatabase(testNamespace, true, true, true);
      } finally {
        hmsClient.close();
      }
    } finally {
      super.stopIcebergTestEnv();
    }
  }

  @Override
  void initEnv() {
    containerSuite.startHiveContainer();
  }

  @Override
  Map<String, String> getCatalogConfig() {
    Map<String, String> configMap = new HashMap<>();
    configMap.put(
        IcebergConfig.ICEBERG_CONFIG_PREFIX + IcebergConfig.CATALOG_BACKEND.getKey(),
        IcebergCatalogBackend.HIVE.toString().toLowerCase());

    configMap.put(
        IcebergConfig.ICEBERG_CONFIG_PREFIX + IcebergConfig.CATALOG_URI.getKey(),
        String.format(
            "thrift://%s:%d",
            containerSuite.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT));

    configMap.put(
        IcebergConfig.ICEBERG_CONFIG_PREFIX + IcebergConfig.CATALOG_WAREHOUSE.getKey(),
        GravitinoITUtils.genRandomName(
            String.format(
                "hdfs://%s:%d/user/hive/warehouse-hive-encryption",
                containerSuite.getHiveContainer().getContainerIpAddress(),
                HiveContainer.HDFS_DEFAULTFS_PORT)));
    configMap.put(
        IcebergConfig.ICEBERG_CONFIG_PREFIX + CatalogProperties.ENCRYPTION_KMS_IMPL,
        IcebergRESTHiveCatalogIT.TestKeyManagementClient.class.getName());
    return configMap;
  }

  /**
   * Proves the HMS tuple (pointer, previous pointer, key, hash) follows an encrypted table through
   * create, commit, drop, register and same-key register-overwrite.
   */
  @Test
  void testAtomicEncryptedMetadataIntegrityInHms() throws Exception {
    String namespace = testNamespace;
    String tableName = "atomic_integrity";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    try (RESTCatalog catalog = newRestCatalog()) {
      Table table = createTable(catalog, identifier, ENCRYPTION_KEY_ID);

      HiveMetaStoreClient hmsClient = createHmsClient();
      try {
        TableMetadata createdMetadata = ((BaseTable) table).operations().current();
        HmsIntegrityState created = loadIntegrityState(hmsClient, namespace, tableName);
        Assertions.assertNull(created.previousMetadataLocation);
        assertIntegrityState(created, createdMetadata);

        table.updateProperties().set("integrity.revision", "2").commit();

        TableMetadata updatedMetadata = ((BaseTable) table).operations().current();
        HmsIntegrityState updated = loadIntegrityState(hmsClient, namespace, tableName);
        Assertions.assertNotEquals(created.metadataLocation, updated.metadataLocation);
        Assertions.assertEquals(created.metadataLocation, updated.previousMetadataLocation);
        Assertions.assertNotEquals(created.metadataHash, updated.metadataHash);
        assertIntegrityState(updated, updatedMetadata);

        Assertions.assertTrue(catalog.dropTable(identifier, false));
        catalog.registerTable(identifier, createdMetadata.metadataFileLocation(), false);

        HmsIntegrityState registered = loadIntegrityState(hmsClient, namespace, tableName);
        Assertions.assertNull(registered.previousMetadataLocation);
        assertIntegrityState(registered, createdMetadata);

        catalog.registerTable(identifier, updatedMetadata.metadataFileLocation(), true);

        HmsIntegrityState overwritten = loadIntegrityState(hmsClient, namespace, tableName);
        Assertions.assertEquals(
            createdMetadata.metadataFileLocation(), overwritten.previousMetadataLocation);
        assertIntegrityState(overwritten, updatedMetadata);

        catalog.invalidateTable(identifier);
        Assertions.assertEquals(
            "2", catalog.loadTable(identifier).properties().get("integrity.revision"));
      } finally {
        hmsClient.close();
      }
    }
  }

  /**
   * Proves native Hive integrity failures keep their native HTTP status: a wrong hash and a key
   * mismatch without a hash are unmapped 500s, and an undecodable hash is a 400.
   */
  @Test
  void testNativeIntegrityFailuresSurfaceNativeStatuses() throws Exception {
    String namespace = testNamespace;
    String tableName = "native_statuses";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    try (RESTCatalog catalog = newRestCatalog();
        HmsTupleFixture hms = new HmsTupleFixture(namespace, tableName)) {
      createCommittedEncryptedTable(catalog, identifier);
      HmsIntegrityState trusted = hms.snapshot();

      hms.alter(parameters -> parameters.put(METADATA_HASH, WRONG_METADATA_HASH));
      Assertions.assertEquals(trusted.metadataLocation, hms.snapshot().metadataLocation);
      assertNativeLoadFailure(identifier, 500, RuntimeException.class.getSimpleName());
      assertClientLoadFails(catalog, identifier, ServiceFailureException.class);

      hms.alter(parameters -> parameters.put(METADATA_HASH, MALFORMED_METADATA_HASH));
      assertNativeLoadFailure(identifier, 400, IllegalArgumentException.class.getSimpleName());
      // The REST client rebuilds a 400 typed IllegalArgumentException as that exception.
      assertClientLoadFails(catalog, identifier, IllegalArgumentException.class);

      hms.alter(
          parameters -> {
            parameters.remove(METADATA_HASH);
            parameters.put(TableProperties.ENCRYPTION_TABLE_KEY, "tampered-kms-key");
          });
      assertNativeLoadFailure(identifier, 500, RuntimeException.class.getSimpleName());
      assertClientLoadFails(catalog, identifier, ServiceFailureException.class);
    }
  }

  /**
   * Proves a table whose HMS record lost its hash but kept its key still loads: native Iceberg
   * falls back to the key comparison (with a WARN) and does not rewrite HMS.
   */
  @Test
  void testMissingHashFallsBackToKeyCompareWithWarning() throws Exception {
    String namespace = testNamespace;
    String tableName = "missing_hash_fallback";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    try (RESTCatalog catalog = newRestCatalog();
        HmsTupleFixture hms = new HmsTupleFixture(namespace, tableName)) {
      createCommittedEncryptedTable(catalog, identifier);
      HmsIntegrityState trusted = hms.snapshot();

      hms.alter(parameters -> parameters.remove(METADATA_HASH));
      evictServerCache(identifier);
      assertLoadResponse(identifier, 200, null);
      catalog.invalidateTable(identifier);
      Assertions.assertEquals(
          ENCRYPTION_KEY_ID,
          catalog.loadTable(identifier).properties().get(TableProperties.ENCRYPTION_TABLE_KEY));

      HmsIntegrityState afterLoad = hms.snapshot();
      Assertions.assertEquals(trusted.metadataLocation, afterLoad.metadataLocation);
      Assertions.assertNull(afterLoad.metadataHash);
    }
  }

  /**
   * Proves the server cache keeps serving a verified snapshot while the HMS pointer is unchanged,
   * and reloads with native verification as soon as the pointer moves.
   */
  @Test
  void testServerCacheServesUntilHmsPointerMoves() throws Exception {
    String namespace = testNamespace;
    String tableName = "cache_location_gate";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    try (RESTCatalog catalog = newRestCatalog();
        HmsTupleFixture hms = new HmsTupleFixture(namespace, tableName)) {
      TableMetadata previous = createCommittedEncryptedTable(catalog, identifier);
      HmsIntegrityState trusted = hms.snapshot();
      evictServerCache(identifier);
      assertLoadResponse(identifier, 200, null);

      hms.alter(parameters -> parameters.put(METADATA_HASH, WRONG_METADATA_HASH));
      // A warm hit is only expected to skip verification because the pointer did not move.
      Assertions.assertEquals(trusted.metadataLocation, hms.snapshot().metadataLocation);
      Assertions.assertDoesNotThrow(() -> restServerOps().loadTable(identifier));

      hms.alter(
          parameters -> {
            parameters.put(METADATA_HASH, trusted.metadataHash);
            parameters.put(METADATA_LOCATION, previous.metadataFileLocation());
          });
      assertLoadResponse(identifier, 500, RuntimeException.class.getSimpleName());

      hms.alter(
          parameters ->
              parameters.put(METADATA_HASH, IcebergMetadataIntegrity.metadataHash(previous)));
      assertLoadResponse(identifier, 200, null);
      catalog.invalidateTable(identifier);
      Assertions.assertFalse(
          catalog.loadTable(identifier).properties().containsKey("integrity.revision"));
    }
  }

  /**
   * Proves a failing native load is never cached, and that once HMS is repaired the next load
   * verifies, succeeds and is cached again.
   */
  @Test
  void testRepeatedNativeFailureThenRepairReloadsAndCaches() throws Exception {
    String namespace = testNamespace;
    String tableName = "repeated_failure_repair";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    try (RESTCatalog catalog = newRestCatalog();
        HmsTupleFixture hms = new HmsTupleFixture(namespace, tableName)) {
      createCommittedEncryptedTable(catalog, identifier);
      HmsIntegrityState trusted = hms.snapshot();

      hms.alter(parameters -> parameters.put(METADATA_HASH, WRONG_METADATA_HASH));
      assertNativeLoadFailure(identifier, 500, RuntimeException.class.getSimpleName());
      // No eviction here: a failed load must not have left anything in the server cache.
      assertLoadResponse(identifier, 500, RuntimeException.class.getSimpleName());

      hms.alter(parameters -> parameters.put(METADATA_HASH, trusted.metadataHash));
      assertLoadResponse(identifier, 200, null);

      hms.alter(parameters -> parameters.put(METADATA_HASH, WRONG_METADATA_HASH));
      Assertions.assertEquals(trusted.metadataLocation, hms.snapshot().metadataLocation);
      // Served from the cache entry the repaired load wrote; the pointer did not move.
      assertLoadResponse(identifier, 200, null);
    }
  }

  /**
   * Proves that removing the key from the HMS record skips the native check entirely, so the load
   * succeeds with the file's unverified key.
   */
  @Test
  void testRemovedHmsKeySkipsNativeCheck() throws Exception {
    String namespace = testNamespace;
    String tableName = "removed_hms_key";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    try (RESTCatalog catalog = newRestCatalog();
        HmsTupleFixture hms = new HmsTupleFixture(namespace, tableName)) {
      createCommittedEncryptedTable(catalog, identifier);
      HmsIntegrityState trusted = hms.snapshot();

      hms.alter(parameters -> parameters.remove(TableProperties.ENCRYPTION_TABLE_KEY));
      Assertions.assertNotNull(hms.snapshot().metadataHash);
      evictServerCache(identifier);
      assertLoadResponse(identifier, 200, null);
      catalog.invalidateTable(identifier);
      Assertions.assertEquals(
          trusted.keyId,
          catalog.loadTable(identifier).properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    }
  }

  /**
   * Proves register-overwrite cannot add, change or strip an encryption key on Hive: each attempt
   * is a 400 and leaves the HMS tuple untouched.
   */
  @Test
  void testRegisterOverwriteRejectsKeyTransitionsWith400() throws Exception {
    String namespace = testNamespace;
    TableIdentifier encrypted = TableIdentifier.of(Namespace.of(namespace), "overwrite_encrypted");
    TableIdentifier otherKey = TableIdentifier.of(Namespace.of(namespace), "overwrite_other_key");
    TableIdentifier plain = TableIdentifier.of(Namespace.of(namespace), "overwrite_plain");

    HiveMetaStoreClient hmsClient = createHmsClient();
    try (RESTCatalog catalog = newRestCatalog()) {
      String encryptedFile =
          currentMetadataLocation(createTable(catalog, encrypted, ENCRYPTION_KEY_ID));
      String otherKeyFile =
          currentMetadataLocation(createTable(catalog, otherKey, SECOND_ENCRYPTION_KEY_ID));
      String plainFile = currentMetadataLocation(createTable(catalog, plain, null));

      // Rekey: encrypted table <- a file bound to another key.
      assertRegisterOverwriteRejected(catalog, hmsClient, encrypted, otherKeyFile);
      // Key added: plain table <- an encrypted file.
      assertRegisterOverwriteRejected(catalog, hmsClient, plain, encryptedFile);
      // Strip: encrypted table <- a plain file.
      assertRegisterOverwriteRejected(catalog, hmsClient, encrypted, plainFile);
    } finally {
      hmsClient.close();
    }
  }

  /**
   * Proves an encrypted Hive table renames with its HMS tuple intact, refuses a purge while its
   * hash does not verify, purges once repaired, and drops without purge without loading metadata.
   */
  @Test
  void testRenameAndPurgeEncryptedTableNatively() throws Exception {
    String namespace = testNamespace;
    String sourceName = "rename_source";
    String targetName = "rename_target";
    String droppedName = "drop_without_purge";
    TableIdentifier source = TableIdentifier.of(Namespace.of(namespace), sourceName);
    TableIdentifier target = TableIdentifier.of(Namespace.of(namespace), targetName);
    TableIdentifier dropped = TableIdentifier.of(Namespace.of(namespace), droppedName);

    HiveMetaStoreClient hmsClient = createHmsClient();
    try (RESTCatalog catalog = newRestCatalog()) {
      createCommittedEncryptedTable(catalog, source);
      HmsIntegrityState beforeRename = loadIntegrityState(hmsClient, namespace, sourceName);

      catalog.renameTable(source, target);

      HmsIntegrityState afterRename = loadIntegrityState(hmsClient, namespace, targetName);
      Assertions.assertEquals(beforeRename.metadataLocation, afterRename.metadataLocation);
      Assertions.assertEquals(beforeRename.keyId, afterRename.keyId);
      Assertions.assertEquals(beforeRename.metadataHash, afterRename.metadataHash);
      Assertions.assertThrows(
          NoSuchObjectException.class, () -> hmsClient.getTable(namespace, sourceName));
      catalog.invalidateTable(target);
      Assertions.assertEquals(
          ENCRYPTION_KEY_ID,
          catalog.loadTable(target).properties().get(TableProperties.ENCRYPTION_TABLE_KEY));

      try (HmsTupleFixture hms = new HmsTupleFixture(namespace, targetName)) {
        hms.alter(parameters -> parameters.put(METADATA_HASH, WRONG_METADATA_HASH));
        assertNativeLoadFailure(target, 500, RuntimeException.class.getSimpleName());
        Assertions.assertThrows(
            ServiceFailureException.class, () -> catalog.dropTable(target, true));
        Assertions.assertNotNull(hmsClient.getTable(namespace, targetName));
      }

      Assertions.assertTrue(catalog.dropTable(target, true));
      Assertions.assertThrows(
          NoSuchObjectException.class, () -> hmsClient.getTable(namespace, targetName));

      createCommittedEncryptedTable(catalog, dropped);
      org.apache.hadoop.hive.metastore.api.Table droppedHmsTable =
          hmsClient.getTable(namespace, droppedName);
      droppedHmsTable.getParameters().put(METADATA_HASH, WRONG_METADATA_HASH);
      hmsClient.alter_table(namespace, droppedName, droppedHmsTable);
      evictServerCache(dropped);
      Assertions.assertTrue(catalog.dropTable(dropped, false));
      Assertions.assertThrows(
          NoSuchObjectException.class, () -> hmsClient.getTable(namespace, droppedName));
    } finally {
      hmsClient.close();
    }
  }

  private RESTCatalog newRestCatalog() {
    RESTCatalog catalog = new RESTCatalog();
    catalog.initialize(
        "rest",
        ImmutableMap.of(
            CatalogProperties.URI, String.format("http://127.0.0.1:%d/iceberg/", getServerPort())));
    return catalog;
  }

  private static Table createTable(RESTCatalog catalog, TableIdentifier identifier, String keyId) {
    TableBuilder builder =
        catalog
            .buildTable(identifier, RECORD_SCHEMA)
            .withProperty(TableProperties.FORMAT_VERSION, "3")
            .withProperty(TableProperties.METADATA_COMPRESSION, "none");
    if (keyId != null) {
      builder.withProperty(TableProperties.ENCRYPTION_TABLE_KEY, keyId);
    }
    return builder.create();
  }

  /** Creates an encrypted table, commits once, and returns the metadata written by the create. */
  private static TableMetadata createCommittedEncryptedTable(
      RESTCatalog catalog, TableIdentifier identifier) {
    Table table = createTable(catalog, identifier, ENCRYPTION_KEY_ID);
    TableMetadata created = ((BaseTable) table).operations().current();
    table.updateProperties().set("integrity.revision", "2").commit();
    return created;
  }

  private static String currentMetadataLocation(Table table) {
    return ((BaseTable) table).operations().current().metadataFileLocation();
  }

  private void assertRegisterOverwriteRejected(
      RESTCatalog catalog,
      HiveMetaStoreClient hmsClient,
      TableIdentifier identifier,
      String metadataFile)
      throws Exception {
    String namespace = identifier.namespace().level(0);
    HmsIntegrityState before = loadIntegrityState(hmsClient, namespace, identifier.name());
    // A 400 typed IllegalArgumentException on the wire; the REST client rebuilds that exception.
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () -> catalog.registerTable(identifier, metadataFile, true));
    HmsIntegrityState after = loadIntegrityState(hmsClient, namespace, identifier.name());
    Assertions.assertEquals(before.metadataLocation, after.metadataLocation);
    Assertions.assertEquals(before.previousMetadataLocation, after.previousMetadataLocation);
    Assertions.assertEquals(before.keyId, after.keyId);
    Assertions.assertEquals(before.metadataHash, after.metadataHash);
  }

  private static void assertClientLoadFails(
      RESTCatalog catalog, TableIdentifier identifier, Class<? extends Exception> expected) {
    catalog.invalidateTable(identifier);
    Assertions.assertThrowsExactly(expected, () -> catalog.loadTable(identifier));
  }

  private HiveMetaStoreClient createHmsClient() throws Exception {
    HiveConf conf = new HiveConf();
    conf.setVar(
        HiveConf.ConfVars.METASTOREURIS,
        String.format(
            "thrift://%s:%d",
            containerSuite.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT));
    return new HiveMetaStoreClient(conf);
  }

  private static CatalogWrapperForREST restServerOps() {
    return IcebergRESTServerContext.getInstance().catalogWrapperManager().getOps("");
  }

  private static void evictServerCache(TableIdentifier identifier) throws Exception {
    // Client invalidation does not evict the server's ordinary metadata cache.
    TableMetadataCache cache =
        (TableMetadataCache) FieldUtils.readField(restServerOps(), "metadataCache", true);
    if (cache != null) {
      cache.invalidate(identifier);
    }
  }

  /** Evicts the server cache so the load runs native verification, then asserts status and type. */
  private void assertNativeLoadFailure(TableIdentifier identifier, int status, String errorType)
      throws Exception {
    evictServerCache(identifier);
    assertLoadResponse(identifier, status, errorType);
  }

  /**
   * Loads over raw HTTP, without the client's ETag, so the wire status is asserted independently of
   * client remapping and of conditional-GET short-circuits.
   */
  private void assertLoadResponse(TableIdentifier identifier, int status, String errorType)
      throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection)
            URI.create(
                    String.format(
                        "http://127.0.0.1:%d/iceberg/v1/namespaces/%s/tables/%s",
                        getServerPort(),
                        RESTUtil.encodeNamespace(
                            identifier.namespace(), NAMESPACE_SEPARATOR_URLENCODED_UTF_8),
                        RESTUtil.encodeString(identifier.name())))
                .toURL()
                .openConnection();
    connection.setConnectTimeout(30_000);
    connection.setReadTimeout(30_000);
    try {
      Assertions.assertEquals(status, connection.getResponseCode());
      if (errorType == null) {
        return;
      }
      try (InputStream body = connection.getErrorStream()) {
        JsonNode error = new ObjectMapper().readTree(body).path("error");
        Assertions.assertEquals(status, error.path("code").asInt());
        Assertions.assertEquals(errorType, error.path("type").asText());
      }
    } finally {
      connection.disconnect();
    }
  }

  private static HmsIntegrityState loadIntegrityState(
      HiveMetaStoreClient hmsClient, String namespace, String tableName) throws Exception {
    org.apache.hadoop.hive.metastore.api.Table hmsTable = hmsClient.getTable(namespace, tableName);
    Map<String, String> parameters = hmsTable.getParameters();
    return new HmsIntegrityState(
        parameters.get(METADATA_LOCATION),
        parameters.get(BaseMetastoreTableOperations.PREVIOUS_METADATA_LOCATION_PROP),
        parameters.get(TableProperties.ENCRYPTION_TABLE_KEY),
        parameters.get(METADATA_HASH));
  }

  private static void assertIntegrityState(HmsIntegrityState state, TableMetadata metadata) {
    Assertions.assertEquals(metadata.metadataFileLocation(), state.metadataLocation);
    Assertions.assertEquals(ENCRYPTION_KEY_ID, state.keyId);
    Assertions.assertEquals(IcebergMetadataIntegrity.metadataHash(metadata), state.metadataHash);
  }

  /**
   * Edits one table's HMS record directly and restores the trusted tuple (pointer, key, hash) it
   * saw when opened, so a failing assertion never leaves a tampered table behind.
   */
  private final class HmsTupleFixture implements AutoCloseable {
    private final String namespace;
    private final String tableName;
    private final HiveMetaStoreClient hmsClient;
    private HmsIntegrityState original;

    private HmsTupleFixture(String namespace, String tableName) throws Exception {
      this.namespace = namespace;
      this.tableName = tableName;
      this.hmsClient = createHmsClient();
    }

    private HmsIntegrityState snapshot() throws Exception {
      HmsIntegrityState state = loadIntegrityState(hmsClient, namespace, tableName);
      if (original == null) {
        original = state;
      }
      return state;
    }

    private void alter(Consumer<Map<String, String>> edit) throws Exception {
      snapshot();
      org.apache.hadoop.hive.metastore.api.Table hmsTable =
          hmsClient.getTable(namespace, tableName);
      edit.accept(hmsTable.getParameters());
      hmsClient.alter_table(namespace, tableName, hmsTable);
    }

    @Override
    public void close() throws Exception {
      try {
        if (original != null) {
          alter(
              parameters -> {
                restore(parameters, METADATA_LOCATION, original.metadataLocation);
                restore(parameters, TableProperties.ENCRYPTION_TABLE_KEY, original.keyId);
                restore(parameters, METADATA_HASH, original.metadataHash);
              });
          evictServerCache(TableIdentifier.of(Namespace.of(namespace), tableName));
        }
      } finally {
        hmsClient.close();
      }
    }

    private void restore(Map<String, String> parameters, String key, String value) {
      if (value == null) {
        parameters.remove(key);
      } else {
        parameters.put(key, value);
      }
    }
  }

  private static final class HmsIntegrityState {
    private final String metadataLocation;
    private final String previousMetadataLocation;
    private final String keyId;
    private final String metadataHash;

    private HmsIntegrityState(
        String metadataLocation,
        String previousMetadataLocation,
        String keyId,
        String metadataHash) {
      this.metadataLocation = metadataLocation;
      this.previousMetadataLocation = previousMetadataLocation;
      this.keyId = keyId;
      this.metadataHash = metadataHash;
    }
  }
}
