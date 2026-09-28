/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.integration.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.audit.CapturingAuditLogWriter;
import org.apache.gravitino.audit.JsonAuditFormatter;
import org.apache.gravitino.audit.WrittenAuditLine;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.HiveContainer;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.server.DatastratoGravitinoServer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;

/**
 * Gravitino-API acceptance coverage for encrypted Iceberg tables on a Hive metastore: a Gravitino
 * server with a Hive-backed, KMS-bound {@code lakehouse-iceberg} catalog and the Iceberg REST
 * service as its auxiliary.
 */
@Tag("gravitino-docker-test")
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
public class IcebergEncryptionHiveIntegrityIT extends IcebergAuthorizationIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ContainerSuite CONTAINER_SUITE = ContainerSuite.getInstance();
  private static final String SCHEMA_NAME = "hive_encrypted_data";
  private static final String GOVERNED_SCHEMA_NAME = "hive_governed_encrypted_data";
  private static final String POLICY_NAME = "hive-customer-data-encryption";
  private static final String KEYED_CATALOG_NAME = "hive_key_default";
  private static final String KEYED_SCHEMA_NAME = "hive_keyed_catalog_data";
  // Valid Base64 of the right length that cannot be the hash of any real metadata file.
  private static final String WRONG_METADATA_HASH =
      Base64.getEncoder().encodeToString(new byte[32]);

  // The REST client scoped to the keyed catalog; the inherited one is scoped to the default.
  private RESTCatalog keyedIcebergCatalog;

  /**
   * Starts the Hive metastore, then the embedded server with the test KMS provider, key validation
   * on and an in-memory audit log.
   *
   * <p>The server runs in this JVM, and the Iceberg REST service publishes on the server's own
   * event bus, so one capturing writer sees the lines both APIs produce. The JSON formatter is
   * chosen because it is the one rendering whose custom info reads back unambiguously.
   */
  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    CONTAINER_SUITE.startHiveContainer();
    RecordingKmsClientFactory.reset();
    customConfigs.put(Configs.ENCRYPTION_VALIDATE_KEYS.getKey(), "true");
    customConfigs.put("gravitino.kms.providers", RecordingKmsClientFactory.PROVIDER);
    customConfigs.put(
        "gravitino.kms.provider." + RecordingKmsClientFactory.PROVIDER + ".className",
        RecordingKmsClientFactory.class.getName());
    CapturingAuditLogWriter.clear();
    customConfigs.put(Configs.AUDIT_LOG_ENABLED_CONF.getKey(), "true");
    customConfigs.put(
        Configs.AUDIT_LOG_WRITER_CLASS_NAME.getKey(), CapturingAuditLogWriter.class.getName());
    customConfigs.put(
        Configs.AUDIT_LOG_FORMATTER_CLASS_NAME.getKey(), JsonAuditFormatter.class.getName());
    super.startIntegrationTest();
    initializeSchemasAndPolicy();
    initializeKeyedCatalog();
  }

  /** Closes the keyed catalog's own REST client, then stops the server. */
  @AfterAll
  @Override
  public void stopIntegrationTest() throws IOException, InterruptedException {
    if (keyedIcebergCatalog != null) {
      try {
        keyedIcebergCatalog.close();
      } catch (IOException e) {
        // Closing a test client must not hide the server shutdown below.
      }
    }
    super.stopIntegrationTest();
  }

  /** Starts the enterprise server whose table dispatcher enforces encryption policy. */
  @Override
  protected void startMiniGravitinoServer(String[] args) {
    DatastratoGravitinoServer.main(args);
  }

  /** Rebinds the catalog to the Hive metastore, KMS-bound and without a server metadata cache. */
  @Override
  protected Map<String, String> getCustomProperties() {
    Map<String, String> properties = new HashMap<>();
    properties.put(IcebergConstants.CATALOG_BACKEND, "hive");
    properties.put(
        IcebergConstants.URI,
        String.format(
            "thrift://%s:%d",
            CONTAINER_SUITE.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT));
    properties.put(
        IcebergConstants.WAREHOUSE,
        GravitinoITUtils.genRandomName(
            String.format(
                "hdfs://%s:%d/user/hive/warehouse-encryption-integrity",
                CONTAINER_SUITE.getHiveContainer().getContainerIpAddress(),
                HiveContainer.HDFS_DEFAULTFS_PORT)));
    properties.put(IcebergConstants.ENCRYPTION_KMS_SOURCE, RecordingKmsClientFactory.PROVIDER);
    // Cold loads on both APIs, so every load runs native Hive verification.
    properties.put(IcebergConstants.TABLE_METADATA_CACHE_IMPL, "");
    properties.put(
        "gravitino.bypass." + CatalogProperties.ENCRYPTION_KMS_IMPL,
        IcebergRESTHiveCatalogIT.TestKeyManagementClient.class.getName());
    return properties;
  }

  /**
   * Proves an encrypted Hive table created through the IRC is readable through the Gravitino API
   * with its key, and that HMS holds the native metadata hash for it.
   */
  @Test
  void testGravitinoApiLoadsIrcCreatedEncryptedTable() throws Exception {
    String tableName = "irc_created_encrypted";
    TableIdentifier icebergIdentifier = TableIdentifier.of(Namespace.of(SCHEMA_NAME), tableName);
    createIrcEncryptedTable(icebergIdentifier);

    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        catalogClientWithAllPrivilege
            .asTableCatalog()
            .loadTable(NameIdentifier.of(SCHEMA_NAME, tableName))
            .properties()
            .get(TableProperties.ENCRYPTION_TABLE_KEY));
    assertHmsTupleMatchesCurrentMetadata(SCHEMA_NAME, tableName);
  }

  /**
   * Proves a governed Gravitino-API create on Hive validates its key with the KMS exactly once and
   * writes the full HMS tuple that native verification relies on.
   */
  @Test
  void testGravitinoApiGovernedCreateWritesHmsTupleOnce() throws Exception {
    String tableName = "governed_created_encrypted";
    RecordingKmsClientFactory.reset();

    createGravitinoEncryptedTable(GOVERNED_SCHEMA_NAME, tableName);

    // One admission per terminal create; nothing on the Hive commit path asks the KMS again.
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    assertHmsTupleMatchesCurrentMetadata(GOVERNED_SCHEMA_NAME, tableName);
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        adminIcebergCatalog
            .loadTable(TableIdentifier.of(Namespace.of(GOVERNED_SCHEMA_NAME), tableName))
            .properties()
            .get(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  /**
   * Proves a native Hive integrity failure reaches the Gravitino API as an untyped 500, not as the
   * JDBC backend's typed {@link MetadataIntegrityException}, and the IRC as a plain 500, and that
   * both refusals are audited as {@code NATIVE_LOAD_FAILURE} on the {@code HIVE} backend.
   */
  @Test
  void testGravitinoApiSurfacesNativeFailureAsUntyped500() throws Exception {
    String tableName = "native_failure_encrypted";
    TableIdentifier icebergIdentifier = TableIdentifier.of(Namespace.of(SCHEMA_NAME), tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    createIrcEncryptedTable(icebergIdentifier);

    HiveMetaStoreClient hmsClient = createHmsClient();
    org.apache.hadoop.hive.metastore.api.Table hmsTable =
        hmsClient.getTable(SCHEMA_NAME, tableName);
    String trustedHash =
        hmsTable.getParameters().get(BaseMetastoreTableOperations.METADATA_HASH_PROP);
    try {
      hmsTable
          .getParameters()
          .put(BaseMetastoreTableOperations.METADATA_HASH_PROP, WRONG_METADATA_HASH);
      hmsClient.alter_table(SCHEMA_NAME, tableName, hmsTable);
      // Only the two refusals below may be audited from here on; every earlier load succeeded.
      CapturingAuditLogWriter.clear();

      Executable gravitinoLoad =
          () -> catalogClientWithAllPrivilege.asTableCatalog().loadTable(gravitinoIdentifier);
      RuntimeException gravitinoFailure =
          Assertions.assertThrowsExactly(RuntimeException.class, gravitinoLoad);
      Assertions.assertNotEquals(MetadataIntegrityException.class, gravitinoFailure.getClass());

      adminIcebergCatalog.invalidateTable(icebergIdentifier);
      Assertions.assertThrowsExactly(
          ServiceFailureException.class, () -> adminIcebergCatalog.loadTable(icebergIdentifier));

      assertNativeLoadFailureAudited();
    } finally {
      hmsTable.getParameters().put(BaseMetastoreTableOperations.METADATA_HASH_PROP, trustedHash);
      hmsClient.alter_table(SCHEMA_NAME, tableName, hmsTable);
      hmsClient.close();
    }
  }

  /** A Hive CTAS publishes its version, catalog key and matching native integrity hash. */
  @Test
  void testCtasOnAKeyedHiveCatalogPublishesVersionKeyAndIntegrity() throws Exception {
    String tableName = "hive_ctas";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(KEYED_SCHEMA_NAME), tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(KEYED_SCHEMA_NAME, tableName);
    Schema schema =
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id"));

    RecordingKmsClientFactory.reset();
    Transaction transaction =
        keyedIcebergCatalog.buildTable(identifier, schema).createTransaction();
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    Assertions.assertFalse(keyedIcebergCatalog.tableExists(identifier));
    transaction.commitTransaction();
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());

    Table loaded = keyedIcebergCatalog.loadTable(identifier);
    TableMetadata committed = ((HasTableOperations) loaded).operations().current();
    Assertions.assertEquals(3, committed.formatVersion());
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        loaded.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        RecordingKmsClientFactory.PROVIDER,
        loaded.properties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));

    HiveMetaStoreClient hmsClient = createHmsClient();
    try {
      org.apache.hadoop.hive.metastore.api.Table hmsTable =
          hmsClient.getTable(KEYED_SCHEMA_NAME, tableName);
      Assertions.assertEquals(
          committed.metadataFileLocation(),
          hmsTable.getParameters().get(BaseMetastoreTableOperations.METADATA_LOCATION_PROP));
      Assertions.assertEquals(
          IcebergMetadataIntegrity.metadataHash(committed),
          hmsTable.getParameters().get(BaseMetastoreTableOperations.METADATA_HASH_PROP));
    } finally {
      hmsClient.close();
    }

    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        metalakeClientWithAllPrivilege
            .loadCatalog(KEYED_CATALOG_NAME)
            .asTableCatalog()
            .loadTable(gravitinoIdentifier)
            .properties()
            .get(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  /** A Hive CTAS validates its requested key only at commit and publishes nothing on refusal. */
  @Test
  void testCtasUnderAKeyTheKmsRejectsIsRefusedAtCommit() {
    String tableName = "hive_ctas_rejected_key";
    TableIdentifier identifier = TableIdentifier.of(Namespace.of(SCHEMA_NAME), tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    Schema schema =
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id"));

    RecordingKmsClientFactory.reset();
    Transaction transaction =
        adminIcebergCatalog
            .buildTable(identifier, schema)
            .withProperty(TableProperties.FORMAT_VERSION, "3")
            .withProperty(
                TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.MISSING_KEY_ID)
            .createTransaction();
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    Assertions.assertFalse(adminIcebergCatalog.tableExists(identifier));
    Assertions.assertFalse(
        catalogClientWithAllPrivilege.asTableCatalog().tableExists(gravitinoIdentifier));

    Assertions.assertThrowsExactly(BadRequestException.class, transaction::commitTransaction);

    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    Assertions.assertFalse(adminIcebergCatalog.tableExists(identifier));
    Assertions.assertFalse(
        catalogClientWithAllPrivilege.asTableCatalog().tableExists(gravitinoIdentifier));
  }

  /**
   * Creates a second Hive-backed catalog that binds its key as a table default, plus the REST
   * client scoped to it. The key and the version are spelled {@code gravitino.bypass.} so they
   * reach the Iceberg REST server's catalog as well as the Gravitino-embedded one.
   *
   * <p>The operator pairs the key with {@code table-default.format-version=3}; Gravitino reads it
   * and never writes it.
   */
  private void initializeKeyedCatalog() {
    Map<String, String> properties = new HashMap<>(getCustomProperties());
    properties.put(
        "gravitino.bypass." + IcebergEncryptionCatalogBinding.TABLE_DEFAULT_KEY_ID,
        RecordingKmsClientFactory.KEY_ID);
    properties.put(
        "gravitino.bypass." + IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3");
    metalakeClientWithAllPrivilege.createCatalog(
        KEYED_CATALOG_NAME,
        Catalog.Type.RELATIONAL,
        "lakehouse-iceberg",
        "hive catalog binding an encryption key",
        properties);
    metalakeClientWithAllPrivilege
        .loadCatalog(KEYED_CATALOG_NAME)
        .asSchemas()
        .createSchema(KEYED_SCHEMA_NAME, "keyed hive catalog acceptance", new HashMap<>());

    Map<String, String> clientProperties = new HashMap<>();
    clientProperties.put(CatalogProperties.URI, getIcebergRestServiceUri());
    // The warehouse selects the named IRC catalog, which becomes the request prefix.
    clientProperties.put(CatalogProperties.WAREHOUSE_LOCATION, KEYED_CATALOG_NAME);
    clientProperties.put(CatalogProperties.CACHE_ENABLED, "false");
    clientProperties.put("rest.auth.type", "basic");
    clientProperties.put("rest.auth.basic.username", SUPER_USER);
    clientProperties.put("rest.auth.basic.password", "mock");
    keyedIcebergCatalog = new RESTCatalog();
    keyedIcebergCatalog.setConf(new Configuration());
    keyedIcebergCatalog.initialize("admin_" + KEYED_CATALOG_NAME, clientProperties);
  }

  private void initializeSchemasAndPolicy() {
    catalogClientWithAllPrivilege
        .asSchemas()
        .createSchema(SCHEMA_NAME, "hive integrity acceptance", new HashMap<>());
    catalogClientWithAllPrivilege
        .asSchemas()
        .createSchema(GOVERNED_SCHEMA_NAME, "governed hive integrity acceptance", new HashMap<>());
    metalakeClientWithAllPrivilege.createPolicy(
        POLICY_NAME,
        Policy.BuiltInType.ICEBERG_ENCRYPTION.policyType(),
        "hive integrity acceptance policy",
        true,
        PolicyContents.icebergEncryption(
            IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
            true,
            List.of(
                new KmsReference(
                    RecordingKmsClientFactory.PROVIDER, RecordingKmsClientFactory.KEY_ID)),
            IcebergEncryptionContent.Enforcement.DENY_CREATE));
    catalogClientWithAllPrivilege
        .asSchemas()
        .loadSchema(GOVERNED_SCHEMA_NAME)
        .supportsPolicies()
        .associatePolicies(new String[] {POLICY_NAME}, null);
  }

  private void createIrcEncryptedTable(TableIdentifier identifier) {
    Schema schema =
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id"));
    adminIcebergCatalog
        .buildTable(identifier, schema)
        .withProperty(TableProperties.FORMAT_VERSION, "3")
        .withProperty(TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.KEY_ID)
        .withProperty(TableProperties.METADATA_COMPRESSION, "none")
        .create();
  }

  private void createGravitinoEncryptedTable(String schemaName, String tableName) {
    Map<String, String> properties = new HashMap<>();
    properties.put(TableProperties.FORMAT_VERSION, "3");
    properties.put(
        IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER,
        RecordingKmsClientFactory.PROVIDER);
    properties.put(
        IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, RecordingKmsClientFactory.KEY_ID);
    Column id = Column.of("id", org.apache.gravitino.rel.types.Types.LongType.get(), "record id");
    catalogClientWithAllPrivilege
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            new Column[] {id},
            "hive integrity acceptance",
            properties);
  }

  private void assertHmsTupleMatchesCurrentMetadata(String schemaName, String tableName)
      throws Exception {
    TableMetadata current =
        ((BaseTable)
                adminIcebergCatalog.loadTable(
                    TableIdentifier.of(Namespace.of(schemaName), tableName)))
            .operations()
            .current();
    HiveMetaStoreClient hmsClient = createHmsClient();
    try {
      Map<String, String> parameters = hmsClient.getTable(schemaName, tableName).getParameters();
      Assertions.assertEquals(
          current.metadataFileLocation(),
          parameters.get(BaseMetastoreTableOperations.METADATA_LOCATION_PROP));
      Assertions.assertEquals(
          RecordingKmsClientFactory.KEY_ID, parameters.get(TableProperties.ENCRYPTION_TABLE_KEY));
      Assertions.assertEquals(
          IcebergMetadataIntegrity.metadataHash(current),
          parameters.get(BaseMetastoreTableOperations.METADATA_HASH_PROP));
    } finally {
      hmsClient.close();
    }
  }

  /**
   * Asserts that the two refusals just observed each reached the audit log as {@code LOAD_TABLE}
   * failure lines, from both the Iceberg REST server and the Gravitino server, every one carrying
   * the facts the failure classifier names structurally for a bare Hive refusal: the {@code
   * NATIVE_LOAD_FAILURE} reason, the {@code RuntimeException} class it is keyed on, the {@code
   * HIVE} backend, that the catalog is governed, and its atomic-integrity capability.
   *
   * <p>The Gravitino API writes exactly one line. The Iceberg REST server may write several: a bare
   * Hive refusal answers 500, and the Iceberg REST client retries an idempotent request on 500
   * ({@code ExponentialHttpRequestRetryStrategy}), so each retry is audited as its own failure.
   *
   * <p>The audit listener is asynchronous, so the lines are awaited. Nothing here passes on an
   * empty log: a refusal that never produced a line fails on the timeout, and the message carries
   * whatever lines did arrive.
   */
  private static void assertNativeLoadFailureAudited() {
    Awaitility.await("an audited LOAD_TABLE failure from each API")
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                Assertions.assertEquals(
                    Set.of(
                        EventSource.GRAVITINO_ICEBERG_REST_SERVER.name(),
                        EventSource.GRAVITINO_SERVER.name()),
                    loadTableFailureLines().stream()
                        .map(IcebergEncryptionHiveIntegrityIT::eventSource)
                        .collect(Collectors.toSet()),
                    () -> "audit lines written: " + CapturingAuditLogWriter.lines()));

    List<WrittenAuditLine> lines = loadTableFailureLines();
    Assertions.assertEquals(
        1,
        lines.stream()
            .filter(line -> EventSource.GRAVITINO_SERVER.name().equals(eventSource(line)))
            .count(),
        () -> "one Gravitino API line: " + rawLines(lines));
    for (WrittenAuditLine line : lines) {
      Map<String, String> facts = line.customInfo();
      Assertions.assertEquals(
          IcebergEncryptionAuditInfos.Reason.NATIVE_LOAD_FAILURE.code(),
          facts.get(IcebergEncryptionAuditInfos.REASON),
          line.raw());
      Assertions.assertEquals(
          RuntimeException.class.getName(),
          facts.get(IcebergEncryptionAuditInfos.ERROR_TYPE),
          line.raw());
      Assertions.assertEquals(
          IcebergCatalogBackend.HIVE.name(),
          facts.get(IcebergEncryptionAuditInfos.BACKEND),
          line.raw());
      Assertions.assertEquals(
          "true", facts.get(IcebergEncryptionAuditInfos.ENCRYPTION_GOVERNED), line.raw());
      String integrityExpected = facts.get(IcebergEncryptionAuditInfos.INTEGRITY_EXPECTED);
      Assertions.assertEquals(
          AtomicIntegrityCapability.SUPPORTED.name(), integrityExpected, line.raw());
    }
  }

  /** The captured {@code LOAD_TABLE} failure lines, oldest first. */
  private static List<WrittenAuditLine> loadTableFailureLines() {
    return CapturingAuditLogWriter.lines().stream()
        .map(WrittenAuditLine::parse)
        .filter(line -> OperationType.LOAD_TABLE.name().equals(line.operation()))
        .filter(line -> OperationStatus.FAILURE.name().equals(line.status()))
        .collect(Collectors.toList());
  }

  /** The server a line came from; the JSON rendering carries it, the parsed line does not. */
  private static String eventSource(WrittenAuditLine line) {
    try {
      return OBJECT_MAPPER.readTree(line.raw()).path("eventSource").asText();
    } catch (IOException e) {
      throw new IllegalStateException("Not a JSON audit line: " + line.raw(), e);
    }
  }

  private static String rawLines(List<WrittenAuditLine> lines) {
    return lines.stream().map(WrittenAuditLine::raw).collect(Collectors.joining("\n"));
  }

  private static HiveMetaStoreClient createHmsClient() throws Exception {
    HiveConf conf = new HiveConf();
    conf.setVar(
        HiveConf.ConfVars.METASTOREURIS,
        String.format(
            "thrift://%s:%d",
            CONTAINER_SUITE.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT));
    return new HiveMetaStoreClient(conf);
  }
}
