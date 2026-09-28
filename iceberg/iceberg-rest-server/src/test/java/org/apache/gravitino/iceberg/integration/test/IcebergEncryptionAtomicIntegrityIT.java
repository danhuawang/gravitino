/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.integration.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.audit.CapturingAuditLogWriter;
import org.apache.gravitino.audit.JsonAuditFormatter;
import org.apache.gravitino.audit.WrittenAuditLine;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.encryption.EncryptionAdmissionService.FailureKind;
import org.apache.gravitino.encryption.IcebergEncryptionDecision;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.cache.TableMetadataCache;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.server.DatastratoGravitinoServer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Catalog.TableBuilder;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequestParser;
import org.apache.iceberg.types.Types;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-API acceptance coverage for catalog-local JDBC metadata integrity and encryption admission,
 * on PostgreSQL and MySQL.
 */
@Tag("gravitino-docker-test")
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
public class IcebergEncryptionAtomicIntegrityIT extends IcebergAuthorizationIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ContainerSuite CONTAINER_SUITE = ContainerSuite.getInstance();
  private static final String SCHEMA_NAME = "encrypted_data";
  private static final String GOVERNED_SCHEMA_NAME = "governed_encrypted_data";
  private static final String POLICY_NAME = "customer-data-encryption";
  private static final String TABLE_NAME = "customer_records";
  private static final String DISALLOWED_KEY_ID = "disallowed-key";
  private static final String TAMPERED_KEY_ID = "tampered-key";
  private static final String UNSUPPORTED_MEMORY_CATALOG = "unsupported_memory";
  private static final String REVISION_PROPERTY = "registration.revision";
  // The documented phrase an UNSUPPORTED_BACKEND refusal carries outward.
  private static final String UNSUPPORTED_BACKEND_PHRASE = "integrity store unavailable";
  private static final String ICEBERG_METADATA_INTEGRITY_PUBLIC_MESSAGE =
      "Iceberg table metadata integrity verification failed";
  private static final String GRAVITINO_METADATA_INTEGRITY_PUBLIC_MESSAGE =
      "Metadata integrity verification failed";
  private static final String IRC_INTEGRITY_FAILURE_PREFIX =
      "Unable to process (code: 412, type: MetadataIntegrityException)";
  private static final String TAMPERED_HASH = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String CREATE_REGISTRATION_REJECTION = "reject_atomic_registration_create";
  private static final String OVERWRITE_REGISTRATION_REJECTION =
      "reject_atomic_registration_overwrite";

  private final Map<String, RESTCatalog> restCatalogs = new HashMap<>();

  /**
   * Starts both database engines, the embedded server with the test KMS, key validation on and an
   * in-memory audit log, and every catalog.
   *
   * <p>The server runs in this JVM, and the Iceberg REST service publishes on the server's own
   * event bus, so one capturing writer sees the lines both APIs produce. The JSON formatter is
   * chosen because it is the one rendering whose custom info reads back unambiguously.
   */
  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
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
    CONTAINER_SUITE.startMySQLContainer(TestDatabaseName.MYSQL_ICEBERG_INTEGRITY_IT);
    CONTAINER_SUITE
        .getMySQLContainer()
        .createDatabase(TestDatabaseName.MYSQL_ICEBERG_INTEGRITY_PLAIN_MODE_IT);
    super.startIntegrationTest();
    CONTAINER_SUITE
        .getPostgreSQLContainer()
        .createDatabase(TestDatabaseName.PG_ICEBERG_INTEGRITY_PLAIN_MODE_IT);
    restCatalogs.put(GRAVITINO_CATALOG_NAME, adminIcebergCatalog);
    initializePolicy();
    initializeCatalogs();
  }

  /** Closes the per-catalog REST clients this class opened, then stops the server. */
  @AfterAll
  @Override
  public void stopIntegrationTest() throws IOException, InterruptedException {
    for (Map.Entry<String, RESTCatalog> entry : restCatalogs.entrySet()) {
      if (!GRAVITINO_CATALOG_NAME.equals(entry.getKey())) {
        try {
          entry.getValue().close();
        } catch (IOException e) {
          // Closing a test client must not hide the server shutdown below.
        }
      }
    }
    super.stopIntegrationTest();
  }

  /** Starts the enterprise server whose table dispatcher enforces encryption policy. */
  @Override
  protected void startMiniGravitinoServer(String[] args) {
    DatastratoGravitinoServer.main(args);
  }

  /** {@inheritDoc} */
  @Override
  protected Map<String, String> getCustomProperties() {
    return Map.of(
        IcebergConstants.ENCRYPTION_KMS_SOURCE,
        RecordingKmsClientFactory.PROVIDER,
        IcebergConstants.TABLE_METADATA_CACHE_IMPL,
        "");
  }

  /** An encrypted IRC create writes one verified row, a commit advances it, a tamper fails. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testEncryptedCommitAndTamperFailureAcrossApis(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, TABLE_NAME);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, TABLE_NAME);

    RecordingKmsClientFactory.reset();
    Table table = createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);

    Assertions.assertEquals(
        new KmsReference(RecordingKmsClientFactory.PROVIDER, RecordingKmsClientFactory.KEY_ID),
        RecordingKmsClientFactory.lastReference().orElseThrow(AssertionError::new));
    // No policy governs this schema, yet a requested key is still validated exactly once.
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());

    TrustedRow created = loadTrustedRow(target, SCHEMA_NAME, TABLE_NAME);
    assertTrustedRow(created);
    Assertions.assertNull(created.previousMetadataLocation());

    table.updateProperties().set("acceptance.revision", "2").commit();
    TrustedRow committed = loadTrustedRow(target, SCHEMA_NAME, TABLE_NAME);
    assertTrustedRow(committed);
    assertTrustedRowAdvanced(created, committed);

    assertKeyVisibleOnBothApis(target, icebergIdentifier, gravitinoIdentifier);

    tamperTrustedHash(target, SCHEMA_NAME, TABLE_NAME);
    restCatalog(target).invalidateTable(icebergIdentifier);

    assertIntegrityFailureAcrossApis(target, icebergIdentifier, gravitinoIdentifier, TAMPERED_HASH);
  }

  /** A Gravitino create and an IRC commit share one trusted row that advances once. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testGravitinoCreateThenIrcCommitAdvancesOneTrustedRow(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "gravitino_then_irc";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);

    RecordingKmsClientFactory.reset();
    createGravitinoTable(
        target,
        SCHEMA_NAME,
        tableName,
        RecordingKmsClientFactory.PROVIDER,
        RecordingKmsClientFactory.KEY_ID);
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    TrustedRow created = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(created);

    restCatalog(target)
        .loadTable(icebergIdentifier)
        .updateProperties()
        .set("acceptance.revision", "2")
        .commit();

    TrustedRow committed = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(committed);
    assertTrustedRowAdvanced(created, committed);
    assertKeyVisibleOnBothApis(target, icebergIdentifier, gravitinoIdentifier);
    Assertions.assertEquals(
        "2",
        gravitinoTables(target)
            .loadTable(gravitinoIdentifier)
            .properties()
            .get("acceptance.revision"));
  }

  /** An IRC create and a Gravitino commit share one trusted row that advances once. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testIrcCreateThenGravitinoCommitAdvancesOneTrustedRow(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "irc_then_gravitino";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);

    RecordingKmsClientFactory.reset();
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    TrustedRow created = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(created);

    gravitinoTables(target)
        .alterTable(gravitinoIdentifier, TableChange.setProperty("acceptance.revision", "2"));

    TrustedRow committed = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(committed);
    assertTrustedRowAdvanced(created, committed);
    assertKeyVisibleOnBothApis(target, icebergIdentifier, gravitinoIdentifier);
    Assertions.assertEquals(
        "2",
        restCatalog(target).loadTable(icebergIdentifier).properties().get("acceptance.revision"));
  }

  /** Registering an encrypted file and overwriting it with the same key validate once each. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testEncryptedRegistrationAndSameKeyOverwriteAdvanceOneTrustedRow(Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "registered_encrypted";
    TableIdentifier identifier = icebergIdentifier(GOVERNED_SCHEMA_NAME, tableName);
    RegistrationVersions versions =
        prepareRegistrationVersions(target, identifier, RecordingKmsClientFactory.KEY_ID);

    RecordingKmsClientFactory.reset();
    restCatalog(target).registerTable(identifier, versions.created().metadataFileLocation(), false);

    assertLastKmsReferenceIsTheAllowedKey();
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    TrustedRow registered = loadTrustedRow(target, GOVERNED_SCHEMA_NAME, tableName);
    assertTrustedRow(registered);
    Assertions.assertNull(registered.previousMetadataLocation());
    Assertions.assertEquals(
        versions.created().metadataFileLocation(), registered.metadataLocation());

    restCatalog(target).registerTable(identifier, versions.updated().metadataFileLocation(), true);

    assertLastKmsReferenceIsTheAllowedKey();
    // The overwrite is admitted once more before the backend repoints; nothing validates twice.
    Assertions.assertEquals(2, RecordingKmsClientFactory.inspectionCount());
    TrustedRow overwritten = loadTrustedRow(target, GOVERNED_SCHEMA_NAME, tableName);
    assertTrustedRow(overwritten);
    Assertions.assertEquals(
        versions.updated().metadataFileLocation(), overwritten.metadataLocation());
    Assertions.assertEquals(
        versions.created().metadataFileLocation(), overwritten.previousMetadataLocation());
    Assertions.assertNotEquals(registered.metadataHash(), overwritten.metadataHash());
  }

  /** A rejected integrity write rolls back the pointer on registration and on overwrite. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testEncryptedRegistrationFailuresRollBackPointerAndTrustedRow(Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "registration_rollback";
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    RegistrationVersions versions =
        prepareRegistrationVersions(target, identifier, RecordingKmsClientFactory.KEY_ID);

    addRejectIntegrityWrite(target, CREATE_REGISTRATION_REJECTION, SCHEMA_NAME, tableName);
    try {
      Assertions.assertThrows(
          ServiceFailureException.class,
          () ->
              restCatalog(target)
                  .registerTable(identifier, versions.created().metadataFileLocation(), false));
      Assertions.assertEquals(0, countPointerRows(target, SCHEMA_NAME, tableName));
      Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));
    } finally {
      removeRejectIntegrityWrite(target, CREATE_REGISTRATION_REJECTION);
    }

    restCatalog(target).registerTable(identifier, versions.created().metadataFileLocation(), false);
    TrustedRow beforeOverwrite = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(beforeOverwrite);

    addRejectIntegrityWrite(target, OVERWRITE_REGISTRATION_REJECTION, SCHEMA_NAME, tableName);
    try {
      Assertions.assertThrows(
          ServiceFailureException.class,
          () ->
              restCatalog(target)
                  .registerTable(identifier, versions.updated().metadataFileLocation(), true));
      TrustedRow afterFailedOverwrite = loadTrustedRow(target, SCHEMA_NAME, tableName);
      assertTrustedRowsEqual(beforeOverwrite, afterFailedOverwrite);
      assertTrustedRow(afterFailedOverwrite);
    } finally {
      removeRejectIntegrityWrite(target, OVERWRITE_REGISTRATION_REJECTION);
    }
  }

  /** A metadata file rewritten in place behind an unchanged pointer fails closed on both APIs. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testMetadataFileTamperingFailsClosedAcrossApis(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "tampered_metadata";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID)
        .updateProperties()
        .set("acceptance.revision", "2")
        .commit();
    TrustedRow committed = loadTrustedRow(target, SCHEMA_NAME, tableName);

    tamperMetadata(committed.metadataLocation());
    restCatalog(target).invalidateTable(icebergIdentifier);

    assertIntegrityFailureAcrossApis(target, icebergIdentifier, gravitinoIdentifier, null);
  }

  /** A plain create writes the pointer only: no integrity row, no key, no KMS call. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testPlainTableCreateWritesPointerOnly(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "plain_create";

    RecordingKmsClientFactory.reset();
    createIrcTable(target, icebergIdentifier(SCHEMA_NAME, tableName), null);

    Assertions.assertEquals(1, countPointerRows(target, SCHEMA_NAME, tableName));
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));
    Assertions.assertFalse(
        gravitinoTables(target)
            .loadTable(NameIdentifier.of(SCHEMA_NAME, tableName))
            .properties()
            .containsKey(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
  }

  /** Register-overwrite of a plain table repoints the pointer and never creates a row. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testPlainTableRegisterOverwriteRepointsPointerOnly(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "plain_register_overwrite";
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    RegistrationVersions versions = prepareRegistrationVersions(target, identifier, null);
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));

    RecordingKmsClientFactory.reset();
    restCatalog(target).registerTable(identifier, versions.created().metadataFileLocation(), false);
    restCatalog(target).registerTable(identifier, versions.updated().metadataFileLocation(), true);

    Pointer pointer = loadPointer(target, SCHEMA_NAME, tableName).orElseThrow(AssertionError::new);
    Assertions.assertEquals(versions.updated().metadataFileLocation(), pointer.metadataLocation());
    Assertions.assertEquals(
        versions.created().metadataFileLocation(), pointer.previousMetadataLocation());
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
  }

  /** Adding, changing or stripping the key through register-overwrite is a 400, never a 412. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRegisterOverwriteRejectsKeyTransitionsWith400(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    TableMetadata encryptedFile =
        createAndDropDonor(target, "transition_donor_encrypted", RecordingKmsClientFactory.KEY_ID);
    TableMetadata otherKeyFile =
        createAndDropDonor(
            target, "transition_donor_other_key", RecordingKmsClientFactory.OTHER_KEY_ID);
    TableMetadata plainFile = createAndDropDonor(target, "transition_donor_plain", null);

    String keyAdded = "transition_key_added";
    createIrcTable(target, icebergIdentifier(SCHEMA_NAME, keyAdded), null);
    assertRegisterOverwriteRejectedWith400(target, keyAdded, encryptedFile);
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, keyAdded));

    String rekeyed = "transition_rekey";
    createIrcTable(
        target, icebergIdentifier(SCHEMA_NAME, rekeyed), RecordingKmsClientFactory.KEY_ID);
    assertRegisterOverwriteRejectedWith400(target, rekeyed, otherKeyFile);

    String stripped = "transition_strip";
    createIrcTable(
        target, icebergIdentifier(SCHEMA_NAME, stripped), RecordingKmsClientFactory.KEY_ID);
    assertRegisterOverwriteRejectedWith400(target, stripped, plainFile);
  }

  /** The key identity is immutable on ordinary commits through both APIs, before any KMS call. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testKeyIdIsImmutableOnUpdateThroughBothApis(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "immutable_key";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);
    TrustedRow before = loadTrustedRow(target, SCHEMA_NAME, tableName);
    RESTCatalog irc = restCatalog(target);

    RecordingKmsClientFactory.reset();
    // The immutability guard throws IllegalArgumentException, so the 400 carries that type and
    // Iceberg's client raises a plain IllegalArgumentException, not a BadRequestException. Both
    // APIs therefore show the same type here.
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            irc.loadTable(icebergIdentifier)
                .updateProperties()
                .set(TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.OTHER_KEY_ID)
                .commit());
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            irc.loadTable(icebergIdentifier)
                .updateProperties()
                .remove(TableProperties.ENCRYPTION_TABLE_KEY)
                .commit());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            gravitinoTables(target)
                .alterTable(
                    gravitinoIdentifier,
                    TableChange.setProperty(
                        IcebergConstants.ENCRYPTION_KEY_ID,
                        RecordingKmsClientFactory.OTHER_KEY_ID)));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            gravitinoTables(target)
                .alterTable(
                    gravitinoIdentifier,
                    TableChange.removeProperty(IcebergConstants.ENCRYPTION_KEY_ID)));

    // Provider guard scope (G-1.4): the provider is as immutable as the key id, and the Apache
    // protocol spellings may not be set, because either could bind the table to another KMS.
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            irc.loadTable(icebergIdentifier)
                .updateProperties()
                .set(IcebergConstants.ENCRYPTION_KEY_PROVIDER, "other")
                .commit());
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            irc.loadTable(icebergIdentifier)
                .updateProperties()
                .remove(IcebergConstants.ENCRYPTION_KEY_PROVIDER)
                .commit());
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            irc.loadTable(icebergIdentifier)
                .updateProperties()
                .set(IcebergConstants.ENCRYPTION_KMS_API, "other")
                .commit());
    assertTrustedRowsEqual(before, loadTrustedRow(target, SCHEMA_NAME, tableName));
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());

    // Removing a protocol spelling cannot rebind a KMS, so the commit goes through.
    irc.loadTable(icebergIdentifier)
        .updateProperties()
        .remove(IcebergConstants.ENCRYPTION_KMS_API)
        .commit();
    irc.loadTable(icebergIdentifier)
        .updateProperties()
        .remove(IcebergConstants.ENCRYPTION_KEY_SOURCE)
        .commit();

    Map<String, String> properties = irc.loadTable(icebergIdentifier).properties();
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID, properties.get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        RecordingKmsClientFactory.PROVIDER,
        properties.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    TrustedRow after = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(after);
    Assertions.assertEquals(before.keyId(), after.keyId());
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
  }

  /** A deleted metadata file behind an intact row is a server failure, not an integrity 412. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testMissingMetadataFileFailsWith500NotA412(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "missing_metadata_file";
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    createIrcTable(target, identifier, RecordingKmsClientFactory.KEY_ID);
    TrustedRow before = loadTrustedRow(target, SCHEMA_NAME, tableName);

    deleteFile(before.metadataLocation());

    // Exactly the 500 type: a RESTException carrying 412 would be a different class.
    Assertions.assertThrowsExactly(
        ServiceFailureException.class, () -> restCatalog(target).loadTable(identifier));
    assertTrustedRowsEqual(before, loadTrustedRow(target, SCHEMA_NAME, tableName));
  }

  /** A tampered key in the trusted row fails closed and the tampered value is never echoed. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testTamperedRowKeyFailsClosedAcrossApis(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "tampered_row_key";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);

    updateIntegrityColumn(target, SCHEMA_NAME, tableName, "table_key_id", TAMPERED_KEY_ID);

    assertIntegrityFailureAcrossApis(
        target, icebergIdentifier, NameIdentifier.of(SCHEMA_NAME, tableName), TAMPERED_KEY_ID);
  }

  /** A pointer moved outside the catalog fails closed before its (absent) file is ever read. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testPointerMovedOutOfBandFailsClosedWithoutReadingFile(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "pointer_moved";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    Table table = createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);
    TrustedRow before = loadTrustedRow(target, SCHEMA_NAME, tableName);

    updatePointer(
        target,
        SCHEMA_NAME,
        tableName,
        table.location() + "/metadata/does-not-exist.metadata.json");

    // A 412 rather than a 500 or 404 proves the location mismatch was refused before any read.
    assertIntegrityFailureAcrossApis(
        target, icebergIdentifier, NameIdentifier.of(SCHEMA_NAME, tableName), null);
    assertIntegrityColumnsEqual(before, loadTrustedRow(target, SCHEMA_NAME, tableName));
  }

  /** Registering over an existing table without overwrite is a 409 after one admission. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRegisterExistingWithoutOverwriteReturns409(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "register_existing";
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    BaseTable table =
        (BaseTable) createIrcTable(target, identifier, RecordingKmsClientFactory.KEY_ID);
    String firstVersion = table.operations().current().metadataFileLocation();
    table.updateProperties().set(REVISION_PROPERTY, "2").commit();
    TrustedRow before = loadTrustedRow(target, SCHEMA_NAME, tableName);

    RecordingKmsClientFactory.reset();
    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> restCatalog(target).registerTable(identifier, firstVersion, false));

    assertTrustedRowsEqual(before, loadTrustedRow(target, SCHEMA_NAME, tableName));
    // Admission runs before the exists check (G-2 order), so the file's key was validated once.
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
  }

  /** Register-overwrite of an absent table creates the pointer and the row like a registration. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRegisterOverwriteOfMissingTableCreatesRows(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "register_overwrite_absent";
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    RegistrationVersions versions =
        prepareRegistrationVersions(target, identifier, RecordingKmsClientFactory.KEY_ID);

    RecordingKmsClientFactory.reset();
    restCatalog(target).registerTable(identifier, versions.created().metadataFileLocation(), true);

    TrustedRow registered = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(registered);
    Assertions.assertNull(registered.previousMetadataLocation());
    Assertions.assertEquals(
        versions.created().metadataFileLocation(), registered.metadataLocation());
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
  }

  /** A supported database whose integrity store is missing refuses encrypted input on both APIs. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testPlainModeCatalogDeniesEncryptedInputAsUnsupportedBackend(Engine engine)
      throws SQLException {
    CatalogTarget target = CatalogTarget.plainMode(engine);
    String ircTable = "plain_mode_irc_encrypted";
    String gravitinoTable = "plain_mode_gravitino_encrypted";

    assertPlainUpstreamDatabase(target);

    RecordingKmsClientFactory.reset();
    BadRequestException ircFailure =
        Assertions.assertThrows(
            BadRequestException.class,
            () ->
                restCatalog(target)
                    .buildTable(icebergIdentifier(SCHEMA_NAME, ircTable), recordSchema())
                    .withProperty(TableProperties.FORMAT_VERSION, "3")
                    .withProperty(
                        IcebergConstants.ENCRYPTION_KEY_PROVIDER,
                        RecordingKmsClientFactory.PROVIDER)
                    .withProperty(
                        TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.KEY_ID)
                    .create());
    assertUnsupportedBackend(ircFailure);

    IllegalArgumentException gravitinoFailure =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                createGravitinoTable(
                    target,
                    SCHEMA_NAME,
                    gravitinoTable,
                    RecordingKmsClientFactory.PROVIDER,
                    RecordingKmsClientFactory.KEY_ID));
    assertUnsupportedBackend(gravitinoFailure);

    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    assertTableAbsent(target.name(), SCHEMA_NAME, ircTable);
    assertTableAbsent(target.name(), SCHEMA_NAME, gravitinoTable);
  }

  /** Renames through either API carry the trusted row to the new identity and back. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRenameEncryptedTableCarriesTrustedRowOnBothApis(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String original = "rename_encrypted";
    String renamed = "rename_encrypted_moved";
    String sibling = "rename_encrypted_sibling";
    createIrcTable(
        target, icebergIdentifier(SCHEMA_NAME, original), RecordingKmsClientFactory.KEY_ID);
    createIrcTable(
        target, icebergIdentifier(SCHEMA_NAME, sibling), RecordingKmsClientFactory.KEY_ID);
    TrustedRow before = loadTrustedRow(target, SCHEMA_NAME, original);
    TrustedRow siblingBefore = loadTrustedRow(target, SCHEMA_NAME, sibling);

    restCatalog(target)
        .renameTable(
            icebergIdentifier(SCHEMA_NAME, original), icebergIdentifier(SCHEMA_NAME, renamed));
    assertRowsMoved(target, original, renamed, before);

    gravitinoTables(target)
        .alterTable(NameIdentifier.of(SCHEMA_NAME, renamed), TableChange.rename(original));
    assertRowsMoved(target, renamed, original, before);

    assertTrustedRowsEqual(siblingBefore, loadTrustedRow(target, SCHEMA_NAME, sibling));
  }

  /** Drops through either API, with or without purge, remove the pointer and the row together. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testDropEncryptedTableRemovesTrustedRowOnBothApis(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String ircDropped = "drop_encrypted_irc";
    String gravitinoDropped = "drop_encrypted_gravitino";
    String ircPurged = "drop_encrypted_irc_purge";
    String sibling = "drop_encrypted_sibling";
    for (String tableName : List.of(ircDropped, gravitinoDropped, ircPurged, sibling)) {
      createIrcTable(
          target, icebergIdentifier(SCHEMA_NAME, tableName), RecordingKmsClientFactory.KEY_ID);
    }
    TrustedRow siblingBefore = loadTrustedRow(target, SCHEMA_NAME, sibling);

    Assertions.assertTrue(
        restCatalog(target).dropTable(icebergIdentifier(SCHEMA_NAME, ircDropped), false));
    assertNoRows(target, SCHEMA_NAME, ircDropped);

    Assertions.assertTrue(
        gravitinoTables(target).dropTable(NameIdentifier.of(SCHEMA_NAME, gravitinoDropped)));
    assertNoRows(target, SCHEMA_NAME, gravitinoDropped);

    // Purge loads through the verifying operations first, so success also proves a verified read.
    Assertions.assertTrue(
        restCatalog(target).dropTable(icebergIdentifier(SCHEMA_NAME, ircPurged), true));
    assertNoRows(target, SCHEMA_NAME, ircPurged);

    assertTrustedRowsEqual(siblingBefore, loadTrustedRow(target, SCHEMA_NAME, sibling));
  }

  /** A plain table renames and drops without reading its metadata file or the integrity store. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRenameAndDropPlainTableSkipIntegrityStore(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String original = "plain_rename_drop";
    String renamed = "plain_rename_drop_moved";
    BaseTable table =
        (BaseTable) createIrcTable(target, icebergIdentifier(SCHEMA_NAME, original), null);
    table.updateProperties().set("acceptance.revision", "2").commit();

    // Without its file the table cannot be loaded, so success below proves no read happened.
    deleteFile(table.operations().refresh().metadataFileLocation());

    restCatalog(target)
        .renameTable(
            icebergIdentifier(SCHEMA_NAME, original), icebergIdentifier(SCHEMA_NAME, renamed));
    Assertions.assertEquals(0, countPointerRows(target, SCHEMA_NAME, original));
    Assertions.assertEquals(1, countPointerRows(target, SCHEMA_NAME, renamed));
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, renamed));

    Assertions.assertTrue(
        gravitinoTables(target).dropTable(NameIdentifier.of(SCHEMA_NAME, renamed)));
    assertNoRows(target, SCHEMA_NAME, renamed);
  }

  /** An encrypted table already failing verification refuses rename and drop on both APIs. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRenameAndDropRefuseAlreadyBrokenEncryptedTable(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "broken_rename_drop";
    String movedName = tableName + "_moved";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);
    tamperTrustedHash(target, SCHEMA_NAME, tableName);
    TrustedRow broken = loadTrustedRow(target, SCHEMA_NAME, tableName);

    assertIrcIntegrityFailure(
        () ->
            restCatalog(target)
                .renameTable(icebergIdentifier, icebergIdentifier(SCHEMA_NAME, movedName)));
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () ->
            gravitinoTables(target).alterTable(gravitinoIdentifier, TableChange.rename(movedName)));
    assertIrcIntegrityFailure(() -> restCatalog(target).dropTable(icebergIdentifier, false));
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () -> gravitinoTables(target).dropTable(gravitinoIdentifier));

    assertTrustedRowsEqual(broken, loadTrustedRow(target, SCHEMA_NAME, tableName));
    assertNoRows(target, SCHEMA_NAME, movedName);
  }

  /** An encrypted table without its integrity row loads, and its first same-key commit adds it. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testEncryptedTableWithoutIntegrityRowLoadsAndFirstCommitWritesRow(Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "missing_row_first_commit";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);

    // Stands in for a table written outside an integrity-aware catalog.
    deleteIntegrityRow(target, SCHEMA_NAME, tableName);
    Assertions.assertEquals(1, countPointerRows(target, SCHEMA_NAME, tableName));
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));

    assertKeyVisibleOnBothApis(target, icebergIdentifier, gravitinoIdentifier);

    RecordingKmsClientFactory.reset();
    restCatalog(target)
        .loadTable(icebergIdentifier)
        .updateProperties()
        .set(REVISION_PROPERTY, "2")
        .commit();
    // An ordinary commit is not admitted, so writing the missing row costs no KMS call.
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());

    TrustedRow written = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRow(written);

    assertKeyVisibleOnBothApis(target, icebergIdentifier, gravitinoIdentifier);

    tamperTrustedHash(target, SCHEMA_NAME, tableName);
    // The pointer does not move when only the row is tampered with, so this client would send
    // If-None-Match from its own table cache and the server would answer 304 from the pointer
    // location alone without verifying anything. Drop the client entry so the tamper is judged on
    // a cold load; the separate cache test owns warm-cache behavior.
    restCatalog(target).invalidateTable(icebergIdentifier);
    assertIntegrityFailureAcrossApis(target, icebergIdentifier, gravitinoIdentifier, TAMPERED_HASH);
  }

  /** Without its integrity row, register-overwrite is refused while rename and drop proceed. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testEncryptedTableWithoutIntegrityRowRefusesOverwriteButRenamesAndDrops(Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "missing_row_overwrite";
    String renamed = "missing_row_overwrite_moved";
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    BaseTable table =
        (BaseTable) createIrcTable(target, identifier, RecordingKmsClientFactory.KEY_ID);
    String firstVersion = table.operations().current().metadataFileLocation();
    table.updateProperties().set(REVISION_PROPERTY, "2").commit();
    String secondVersion = table.operations().refresh().metadataFileLocation();

    deleteIntegrityRow(target, SCHEMA_NAME, tableName);

    RecordingKmsClientFactory.reset();
    assertIrcIntegrityFailure(
        () -> restCatalog(target).registerTable(identifier, firstVersion, true));
    // Admission precedes the backend refusal (G-2 order).
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    assertPointerAt(target, tableName, secondVersion);
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));

    restCatalog(target).renameTable(identifier, icebergIdentifier(SCHEMA_NAME, renamed));
    assertNoRows(target, SCHEMA_NAME, tableName);
    Assertions.assertEquals(1, countPointerRows(target, SCHEMA_NAME, renamed));
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, renamed));
    Assertions.assertEquals(
        "2",
        restCatalog(target)
            .loadTable(icebergIdentifier(SCHEMA_NAME, renamed))
            .properties()
            .get(REVISION_PROPERTY));

    Assertions.assertTrue(
        gravitinoTables(target).dropTable(NameIdentifier.of(SCHEMA_NAME, renamed)));
    assertNoRows(target, SCHEMA_NAME, renamed);
  }

  /** With the server cache on, a cached table is served until its backend pointer moves. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testWarmCacheServesCachedMetadataUntilPointerMoves(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.cached(engine);
    String tableName = "warm_location_gate";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    RESTCatalog irc = restCatalog(target);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);
    irc.loadTable(icebergIdentifier);
    TrustedRow warm = loadTrustedRow(target, SCHEMA_NAME, tableName);

    // (i) A tampered hash behind an unchanged pointer is not re-verified on a cache hit.
    tamperTrustedHash(target, SCHEMA_NAME, tableName);
    assertPointerAt(target, tableName, warm.metadataLocation());
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        irc.loadTable(icebergIdentifier).properties().get(TableProperties.ENCRYPTION_TABLE_KEY));

    // (ii) A commit elsewhere moves the pointer, so the next load reloads and verifies.
    updateIntegrityColumn(target, SCHEMA_NAME, tableName, "metadata_hash", warm.metadataHash());
    gravitinoTables(target)
        .alterTable(gravitinoIdentifier, TableChange.setProperty("acceptance.revision", "2"));
    TrustedRow moved = loadTrustedRow(target, SCHEMA_NAME, tableName);
    assertTrustedRowAdvanced(warm, moved);
    Assertions.assertEquals(
        "2", irc.loadTable(icebergIdentifier).properties().get("acceptance.revision"));

    // (iii) Warm again behind an unchanged pointer; a pointer moved out of band is re-verified.
    tamperTrustedHash(target, SCHEMA_NAME, tableName);
    assertPointerAt(target, tableName, moved.metadataLocation());
    Assertions.assertEquals(
        "2", irc.loadTable(icebergIdentifier).properties().get("acceptance.revision"));
    updatePointer(target, SCHEMA_NAME, tableName, warm.metadataLocation());
    assertIrcIntegrityFailure(() -> irc.loadTable(icebergIdentifier));

    // (iv) Consistent again so the verifying drop accepts it; a dropped table is a cache miss.
    updatePointer(target, SCHEMA_NAME, tableName, moved.metadataLocation());
    updateIntegrityColumn(target, SCHEMA_NAME, tableName, "metadata_hash", moved.metadataHash());
    Assertions.assertTrue(gravitinoTables(target).dropTable(gravitinoIdentifier));
    Assertions.assertThrows(NoSuchTableException.class, () -> irc.loadTable(icebergIdentifier));
  }

  /** A register-overwrite moves the pointer, so warm caches serve the new version at once. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testWarmCacheFollowsRegisterOverwriteThroughLocationGate(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.cached(engine);
    String tableName = "warm_register_overwrite";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    RESTCatalog irc = restCatalog(target);
    RegistrationVersions versions =
        prepareRegistrationVersions(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);

    irc.registerTable(icebergIdentifier, versions.created().metadataFileLocation(), false);
    Assertions.assertNull(irc.loadTable(icebergIdentifier).properties().get(REVISION_PROPERTY));
    Assertions.assertNull(
        gravitinoTables(target).loadTable(gravitinoIdentifier).properties().get(REVISION_PROPERTY));

    irc.registerTable(icebergIdentifier, versions.updated().metadataFileLocation(), true);
    assertPointerAt(target, tableName, versions.updated().metadataFileLocation());

    // No client or server invalidation in between: the location gate alone notices the move.
    Assertions.assertEquals(
        "2", irc.loadTable(icebergIdentifier).properties().get(REVISION_PROPERTY));
    Assertions.assertEquals(
        "2",
        gravitinoTables(target).loadTable(gravitinoIdentifier).properties().get(REVISION_PROPERTY));
  }

  /** A failing table is never cached; once repaired it loads again and enters the cache. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRepairedTableLoadsAgainAndIsCached(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.cached(engine);
    String tableName = "repaired_after_failure";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(SCHEMA_NAME, tableName);
    RESTCatalog irc = restCatalog(target);
    createIrcTable(target, icebergIdentifier, RecordingKmsClientFactory.KEY_ID);
    TrustedRow row = loadTrustedRow(target, SCHEMA_NAME, tableName);

    tamperTrustedHash(target, SCHEMA_NAME, tableName);
    evictServerCaches(target, SCHEMA_NAME, tableName);
    assertIrcIntegrityFailure(() -> irc.loadTable(icebergIdentifier));
    assertIrcIntegrityFailure(() -> irc.loadTable(icebergIdentifier));
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () -> gravitinoTables(target).loadTable(gravitinoIdentifier));

    updateIntegrityColumn(target, SCHEMA_NAME, tableName, "metadata_hash", row.metadataHash());
    assertKeyVisibleOnBothApis(target, icebergIdentifier, gravitinoIdentifier);

    tamperTrustedHash(target, SCHEMA_NAME, tableName);
    assertPointerAt(target, tableName, row.metadataLocation());
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        irc.loadTable(icebergIdentifier).properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  /** Every denial reason surfaces the same reason code on both APIs, with exact KMS use. */
  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("denialCases")
  void testDeniedCreateReturnsSameKindOnBothApis(Engine engine, Api api, DenialReason reason) {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "denied_" + api.name().toLowerCase() + "_" + reason.name().toLowerCase();

    RecordingKmsClientFactory.reset();
    Exception failure =
        api == Api.IRC
            ? Assertions.assertThrows(
                reason.ircType(),
                () ->
                    createIrcTable(
                        target, icebergIdentifier(GOVERNED_SCHEMA_NAME, tableName), reason.keyId()))
            : Assertions.assertThrows(
                reason.gravitinoType(),
                () ->
                    createGravitinoTable(
                        target,
                        GOVERNED_SCHEMA_NAME,
                        tableName,
                        reason.keyId() == null ? null : RecordingKmsClientFactory.PROVIDER,
                        reason.keyId()));

    // Outward messages carry the decision's reason code, never the internal failure kind.
    Assertions.assertTrue(
        failure.getMessage().contains("reason=" + reason.decisionReason().code()),
        () -> "Unexpected " + reason.kind() + " message: " + failure.getMessage());
    Assertions.assertEquals(reason.kmsInspections(), RecordingKmsClientFactory.inspectionCount());
    assertTableAbsent(target.name(), GOVERNED_SCHEMA_NAME, tableName);
  }

  /** A governed create infers the provider and validates the key once through either API. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testGovernedCreateInfersProviderAndValidatesKeyOnceOnBothApis(Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String ircTable = "governed_once_irc";
    String gravitinoTable = "governed_once_gravitino";

    RecordingKmsClientFactory.reset();
    Table created =
        createIrcTable(
            target,
            icebergIdentifier(GOVERNED_SCHEMA_NAME, ircTable),
            RecordingKmsClientFactory.KEY_ID);
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    assertTrustedRow(loadTrustedRow(target, GOVERNED_SCHEMA_NAME, ircTable));
    Assertions.assertEquals(3, ((BaseTable) created).operations().current().formatVersion());
    Assertions.assertEquals(
        RecordingKmsClientFactory.PROVIDER,
        created.properties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));

    RecordingKmsClientFactory.reset();
    createGravitinoTable(
        target, GOVERNED_SCHEMA_NAME, gravitinoTable, null, RecordingKmsClientFactory.KEY_ID);
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    assertTrustedRow(loadTrustedRow(target, GOVERNED_SCHEMA_NAME, gravitinoTable));
    Map<String, String> properties =
        gravitinoTables(target)
            .loadTable(NameIdentifier.of(GOVERNED_SCHEMA_NAME, gravitinoTable))
            .properties();
    Assertions.assertEquals("3", properties.get(TableProperties.FORMAT_VERSION));
    Assertions.assertEquals(
        RecordingKmsClientFactory.PROVIDER,
        properties.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
  }

  /**
   * SPIP acceptance criterion 2, on both backends: a staged create and its commit — the CTAS shape
   * — succeed on a catalog with a default key and on one with an override key. The stage prepares
   * and the commit decides, so the one KMS validation is the commit's. The published table is at
   * format version 3 under the catalog's key. Without the catalog's {@code
   * table-default.format-version=3} declaration, Iceberg builds the create-commit's placeholder at
   * version 2 and rejects the key before any Enterprise code runs.
   */
  @ParameterizedTest(name = "{0} on {1}")
  @MethodSource("keyedCatalogs")
  void testRestClientStagedCreateCarriesCatalogKeyAndValidatesOnceAtCommit(
      CatalogKeyMode mode, Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.keyed(mode, engine);
    String tableName = "staged_create";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    RESTCatalog irc = restCatalog(target);

    RecordingKmsClientFactory.reset();
    Transaction transaction = irc.buildTable(icebergIdentifier, recordSchema()).createTransaction();
    // The stage is prepared without the KMS; nothing is published yet.
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    assertNoRows(target, SCHEMA_NAME, tableName);

    DataFile dataFile =
        DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath(transaction.table().location() + "/data/staged-create.parquet")
            .withFileSizeInBytes(10)
            .withRecordCount(1)
            .withFormat(FileFormat.PARQUET)
            .build();
    transaction.newAppend().appendFile(dataFile).commit();
    transaction.commitTransaction();

    // The assert-create commit is the authoritative admission and makes the one KMS call.
    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    Assertions.assertEquals(1, countPointerRows(target, SCHEMA_NAME, tableName));
    assertTrustedRow(loadTrustedRow(target, SCHEMA_NAME, tableName));

    Table loaded = irc.loadTable(icebergIdentifier);
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        loaded.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        RecordingKmsClientFactory.PROVIDER,
        loaded.properties().get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals(3, ((BaseTable) loaded).operations().current().formatVersion());

    Map<String, String> gravitinoProperties =
        gravitinoTables(target).loadTable(NameIdentifier.of(SCHEMA_NAME, tableName)).properties();
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        gravitinoProperties.get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals(
        RecordingKmsClientFactory.PROVIDER,
        gravitinoProperties.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));
    Assertions.assertEquals("3", gravitinoProperties.get(TableProperties.FORMAT_VERSION));
  }

  /** A keyless assert-create commit that was never staged cannot bypass a keyed catalog. */
  @ParameterizedTest(name = "{0} on {1}")
  @MethodSource("keyedCatalogs")
  void testUnstagedKeylessAssertCreateOnKeyedCatalogIsRejected(CatalogKeyMode mode, Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.keyed(mode, engine);
    String tableName = "unstaged_assert_create";
    UpdateTableRequest request =
        UpdateTableRequest.create(
            icebergIdentifier(SCHEMA_NAME, tableName),
            List.of(new UpdateRequirement.AssertTableDoesNotExist()),
            List.of(
                new MetadataUpdate.AssignUUID(UUID.randomUUID().toString()),
                new MetadataUpdate.UpgradeFormatVersion(3),
                new MetadataUpdate.AddSchema(recordSchema()),
                new MetadataUpdate.SetCurrentSchema(-1)));

    RecordingKmsClientFactory.reset();
    HttpFailure failure = postUpdateTable(target.name(), SCHEMA_NAME, tableName, request);

    // FailureKind.INVALID_REFERENCE carries neither kind name nor reason code outward, so the
    // observable is the 400 type, raised before policy resolution and before the KMS.
    Assertions.assertEquals(400, failure.status(), FailureKind.INVALID_REFERENCE.name());
    Assertions.assertEquals(BadRequestException.class.getSimpleName(), failure.type());
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    assertTableAbsent(target.name(), SCHEMA_NAME, tableName);
    assertNoRows(target, SCHEMA_NAME, tableName);
  }

  /** Registering a file whose key the policy does not allow is denied before KMS and backend. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testRegistrationDeniedByPolicyLeavesNoRows(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "registration_denied";
    TableMetadata otherKeyFile =
        createAndDropDonor(
            target, "registration_denied_donor", RecordingKmsClientFactory.OTHER_KEY_ID);

    RecordingKmsClientFactory.reset();
    BadRequestException failure =
        Assertions.assertThrowsExactly(
            BadRequestException.class,
            () ->
                restCatalog(target)
                    .registerTable(
                        icebergIdentifier(GOVERNED_SCHEMA_NAME, tableName),
                        otherKeyFile.metadataFileLocation(),
                        false));

    // FailureKind.POLICY_DENIED surfaces as its reason code; the allow list precedes the KMS.
    Assertions.assertTrue(
        failure
            .getMessage()
            .contains("reason=" + IcebergEncryptionDecision.Reason.KEY_NOT_ALLOWED.code()),
        () -> "Unexpected " + FailureKind.POLICY_DENIED + " message: " + failure.getMessage());
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    assertNoRows(target, GOVERNED_SCHEMA_NAME, tableName);
  }

  /** A catalog without atomic integrity refuses encrypted and required creates before the KMS. */
  @Test
  void testUnsupportedBackendDeniesEncryptedInputBeforeKms() {
    String keyedIrc = "unsupported_keyed_irc";
    String keyedGravitino = "unsupported_keyed_gravitino";
    String keylessIrc = "unsupported_keyless_irc";
    String keylessGravitino = "unsupported_keyless_gravitino";
    RESTCatalog irc = restCatalog(UNSUPPORTED_MEMORY_CATALOG);

    RecordingKmsClientFactory.reset();
    assertUnsupportedBackend(
        Assertions.assertThrows(
            BadRequestException.class,
            () ->
                irc.buildTable(icebergIdentifier(GOVERNED_SCHEMA_NAME, keyedIrc), recordSchema())
                    .withProperty(TableProperties.FORMAT_VERSION, "3")
                    .withProperty(
                        TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.KEY_ID)
                    .create()));
    assertUnsupportedBackend(
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                createGravitinoTable(
                    UNSUPPORTED_MEMORY_CATALOG,
                    GOVERNED_SCHEMA_NAME,
                    keyedGravitino,
                    RecordingKmsClientFactory.PROVIDER,
                    RecordingKmsClientFactory.KEY_ID)));
    // The policy requires encryption, so even a keyless create reaches the same refusal.
    assertUnsupportedBackend(
        Assertions.assertThrows(
            BadRequestException.class,
            () ->
                irc.buildTable(icebergIdentifier(GOVERNED_SCHEMA_NAME, keylessIrc), recordSchema())
                    .create()));
    assertUnsupportedBackend(
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                createGravitinoTable(
                    UNSUPPORTED_MEMORY_CATALOG,
                    GOVERNED_SCHEMA_NAME,
                    keylessGravitino,
                    null,
                    null)));

    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    for (String tableName : List.of(keyedIrc, keyedGravitino, keylessIrc, keylessGravitino)) {
      assertTableAbsent(UNSUPPORTED_MEMORY_CATALOG, GOVERNED_SCHEMA_NAME, tableName);
    }
  }

  /**
   * A key with an explicit version 2 is refused, not raised, before the KMS, whether the client
   * names the key on an unkeyed catalog or the catalog overrides every table's key.
   */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("engineAndApi")
  void testKeyWithExplicitVersionTwoIsRefusedAsUnsupportedFormatVersion(Engine engine, Api api) {
    String suffix = "_" + api.name().toLowerCase();
    assertUnsupportedFormatVersion(
        CatalogTarget.base(engine),
        api,
        "client_key_v2" + suffix,
        RecordingKmsClientFactory.KEY_ID,
        "2");
    assertUnsupportedFormatVersion(
        CatalogTarget.keyed(CatalogKeyMode.TABLE_OVERRIDE, engine),
        api,
        "catalog_key_v2" + suffix,
        null,
        "2");
  }

  /** SPIP row 5b: a catalog whose default is 3 creates a plain table at version 3 on both APIs. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testCatalogDefaultThreeCreatesAPlainVersionThreeTable(Engine engine) {
    CatalogTarget target = CatalogTarget.defaultThree(engine);

    RecordingKmsClientFactory.reset();
    Table created = createIrcTable(target, icebergIdentifier(SCHEMA_NAME, "plain_irc"), null, null);
    Assertions.assertEquals(3, ((BaseTable) created).operations().current().formatVersion());
    Assertions.assertNull(created.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));

    createVersionedGravitinoTable(target, "plain_gravitino", null, null);
    Assertions.assertEquals(3, ircFormatVersion(target, "plain_gravitino"));
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
  }

  /**
   * A catalog that binds a key and declares {@code table-default.format-version=3} creates a table
   * that names no version at version 3, encrypted under the catalog's key, through either API. The
   * version is read out of the declaration, not raised: the same create on the undeclared catalog
   * is refused in {@code testAPlainVersionTwoTableKeepsCommittingAfterItsCatalogBindsAKey}.
   */
  @ParameterizedTest(name = "{0} on {1}")
  @MethodSource("keyedCatalogs")
  void testKeyedCatalogCreatesATableThatNamesNoVersionAtVersionThree(
      CatalogKeyMode mode, Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.keyed(mode, engine);

    RecordingKmsClientFactory.reset();
    createIrcTable(target, icebergIdentifier(SCHEMA_NAME, "unversioned_irc"), null, null);
    createVersionedGravitinoTable(target, "unversioned_gravitino", null, null);

    for (String tableName : List.of("unversioned_irc", "unversioned_gravitino")) {
      Assertions.assertEquals(3, ircFormatVersion(target, tableName), tableName);
      assertTrustedRow(loadTrustedRow(target, SCHEMA_NAME, tableName));
    }
    Assertions.assertEquals(2, RecordingKmsClientFactory.inspectionCount());
  }

  /**
   * SPIP rows 18 and 27: a plain version 2 table created before its catalog binds a key keeps
   * committing at version 2 with no key, through both APIs, once the key is bound.
   *
   * <p>Row 8 rides the same catalog, once the key is bound: the catalog declares no {@code
   * table-default.format-version}, so an unversioned create under the new key is refused on both
   * APIs before the KMS, rather than raised to version 3.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testAPlainVersionTwoTableKeepsCommittingAfterItsCatalogBindsAKey(Engine engine)
      throws Exception {
    CatalogTarget target = CatalogTarget.keyedLater(engine);
    String tableName = "plain_before_key";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);
    createIrcTable(target, icebergIdentifier, null, "2");

    metalakeClientWithAllPrivilege.alterCatalog(
        target.name(),
        CatalogChange.setProperty(
            CatalogKeyMode.TABLE_DEFAULT.catalogProperty, RecordingKmsClientFactory.KEY_ID));

    RecordingKmsClientFactory.reset();
    restCatalog(target)
        .loadTable(icebergIdentifier)
        .updateProperties()
        .set("revision", "2")
        .commit();
    gravitinoTables(target)
        .alterTable(
            NameIdentifier.of(SCHEMA_NAME, tableName), TableChange.setProperty("revision", "3"));

    Table reloaded = restCatalog(target).loadTable(icebergIdentifier);
    Assertions.assertEquals(2, ((BaseTable) reloaded).operations().current().formatVersion());
    Assertions.assertEquals("3", reloaded.properties().get("revision"));
    Assertions.assertNull(reloaded.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    assertNoIntegrityRow(target, tableName);

    // Row 8: the bound key now applies to new tables, and the catalog declares no version for it.
    RecordingKmsClientFactory.reset();
    assertNamesTheCatalogDefaultVersion(
        Assertions.assertThrowsExactly(
            IllegalArgumentException.class,
            () ->
                createIrcTable(
                    target, icebergIdentifier(SCHEMA_NAME, "unversioned_irc"), null, null)));
    assertNamesTheCatalogDefaultVersion(
        Assertions.assertThrowsExactly(
            IllegalArgumentException.class,
            () -> createVersionedGravitinoTable(target, "unversioned_gravitino", null, null)));
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    for (String refused : List.of("unversioned_irc", "unversioned_gravitino")) {
      assertTableAbsent(target.name(), SCHEMA_NAME, refused);
      assertNoRows(target, SCHEMA_NAME, refused);
    }
  }

  /**
   * CTAS design test 9: a staged create under a key the KMS rejects passes the stage, which never
   * asks the KMS, and is refused at commit after the one KMS call, leaving no table and no rows.
   * The request names format version 3 so the refusal stays the KMS's.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Engine.class)
  void testCtasUnderAKeyTheKmsRejectsIsRefusedAtCommit(Engine engine) throws Exception {
    CatalogTarget target = CatalogTarget.base(engine);
    String tableName = "ctas_rejected_key";
    TableIdentifier icebergIdentifier = icebergIdentifier(SCHEMA_NAME, tableName);

    RecordingKmsClientFactory.reset();
    Transaction transaction =
        restCatalog(target)
            .buildTable(icebergIdentifier, recordSchema())
            .withProperty(TableProperties.FORMAT_VERSION, "3")
            .withProperty(
                TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.MISSING_KEY_ID)
            .createTransaction();
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());

    Assertions.assertThrows(BadRequestException.class, transaction::commitTransaction);

    Assertions.assertEquals(1, RecordingKmsClientFactory.inspectionCount());
    assertNoRows(target, SCHEMA_NAME, tableName);
    assertTableAbsent(target.name(), SCHEMA_NAME, tableName);
  }

  static Stream<Arguments> engineAndApi() {
    List<Arguments> cases = new ArrayList<>();
    for (Engine engine : Engine.values()) {
      for (Api api : Api.values()) {
        cases.add(Arguments.of(engine, api));
      }
    }
    return cases.stream();
  }

  static Stream<Arguments> denialCases() {
    List<Arguments> cases = new ArrayList<>();
    for (Api api : Api.values()) {
      for (DenialReason reason : DenialReason.values()) {
        cases.add(Arguments.of(Engine.POSTGRESQL, api, reason));
      }
    }
    return cases.stream();
  }

  /** Every way a catalog binds a key on every supported JDBC backend. */
  static Stream<Arguments> keyedCatalogs() {
    List<Arguments> cases = new ArrayList<>();
    for (CatalogKeyMode mode : CatalogKeyMode.values()) {
      for (Engine engine : Engine.values()) {
        cases.add(Arguments.of(mode, engine));
      }
    }
    return cases.stream();
  }

  private void initializePolicy() {
    metalakeClientWithAllPrivilege.createPolicy(
        POLICY_NAME,
        Policy.BuiltInType.ICEBERG_ENCRYPTION.policyType(),
        "atomic integrity acceptance policy",
        true,
        PolicyContents.icebergEncryption(
            IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
            true,
            List.of(
                new KmsReference(
                    RecordingKmsClientFactory.PROVIDER, RecordingKmsClientFactory.KEY_ID),
                new KmsReference(
                    RecordingKmsClientFactory.PROVIDER, RecordingKmsClientFactory.MISSING_KEY_ID),
                new KmsReference(
                    RecordingKmsClientFactory.PROVIDER,
                    RecordingKmsClientFactory.UNAVAILABLE_KEY_ID)),
            IcebergEncryptionContent.Enforcement.DENY_CREATE));
  }

  private void initializeCatalogs() {
    initializeSchemas(GRAVITINO_CATALOG_NAME, true);

    CatalogTarget mysql = CatalogTarget.base(Engine.MYSQL);
    createJdbcCatalog(mysql, true, true, Map.of());
    initializeSchemas(mysql.name(), true);

    for (Engine engine : Engine.values()) {
      CatalogTarget cached = CatalogTarget.cached(engine);
      // The server cache stays at its default implementation on these catalogs.
      createJdbcCatalog(cached, true, false, Map.of());
      initializeSchemas(cached.name(), false);

      CatalogTarget plainMode = CatalogTarget.plainMode(engine);
      provisionUpstreamCatalogTablesOnly(plainMode);
      // Two Iceberg catalogs open this database: the Gravitino catalog behind the Gravitino API
      // and the IRC server's own. Both must skip table initialization, or whichever provisions
      // the integrity store first makes the backend supported again and the test loses its
      // subject. `jdbc-initialize` only reaches the IRC one, because the Gravitino catalog keeps
      // just the properties `IcebergPropertiesUtils.GRAVITINO_CONFIG_TO_ICEBERG` names; the
      // bypass spelling of Iceberg's own property reaches both.
      createJdbcCatalog(
          plainMode, false, true, Map.of("gravitino.bypass.jdbc.init-catalog-tables", "false"));
      initializeSchemas(plainMode.name(), false);
    }

    // SPIP format version rows run on every engine: a catalog defaulting to 3, and one that binds
    // a key only after a plain table exists on it.
    for (Engine engine : Engine.values()) {
      createJdbcCatalog(
          CatalogTarget.defaultThree(engine),
          true,
          true,
          Map.of(
              "gravitino.bypass." + IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
              "3"));
      initializeSchemas(CatalogTarget.defaultThree(engine).name(), false);
      createJdbcCatalog(CatalogTarget.keyedLater(engine), true, true, Map.of());
      initializeSchemas(CatalogTarget.keyedLater(engine).name(), false);
    }

    // The operator pairs the key with `table-default.format-version=3`; Gravitino reads it and
    // never writes it. Every key mode exists on every engine for the staged-create journey.
    for (CatalogKeyMode mode : CatalogKeyMode.values()) {
      for (Engine engine : Engine.values()) {
        CatalogTarget keyed = CatalogTarget.keyed(mode, engine);
        createJdbcCatalog(
            keyed,
            true,
            true,
            Map.of(
                mode.catalogProperty,
                RecordingKmsClientFactory.KEY_ID,
                "gravitino.bypass." + IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
                "3"));
        initializeSchemas(keyed.name(), false);
      }
    }

    metalakeClientWithAllPrivilege.createCatalog(
        UNSUPPORTED_MEMORY_CATALOG,
        Catalog.Type.RELATIONAL,
        "lakehouse-iceberg",
        "catalog without atomic metadata integrity",
        Map.of(
            IcebergConstants.URI,
            "memory://" + UNSUPPORTED_MEMORY_CATALOG,
            IcebergConstants.CATALOG_BACKEND,
            "memory",
            IcebergConstants.CATALOG_BACKEND_NAME,
            UNSUPPORTED_MEMORY_CATALOG,
            IcebergConstants.WAREHOUSE,
            "file:///tmp/" + UNSUPPORTED_MEMORY_CATALOG + "/",
            IcebergConstants.ENCRYPTION_KMS_SOURCE,
            RecordingKmsClientFactory.PROVIDER,
            IcebergConstants.TABLE_METADATA_CACHE_IMPL,
            ""));
    Catalog memory = metalakeClientWithAllPrivilege.loadCatalog(UNSUPPORTED_MEMORY_CATALOG);
    memory
        .asSchemas()
        .createSchema(GOVERNED_SCHEMA_NAME, "unsupported backend acceptance", new HashMap<>());
    memory
        .asSchemas()
        .loadSchema(GOVERNED_SCHEMA_NAME)
        .supportsPolicies()
        .associatePolicies(new String[] {POLICY_NAME}, null);
  }

  private void initializeSchemas(String catalogName, boolean governed) {
    Catalog catalog = metalakeClientWithAllPrivilege.loadCatalog(catalogName);
    catalog.asSchemas().createSchema(SCHEMA_NAME, "atomic integrity acceptance", new HashMap<>());
    if (governed) {
      catalog
          .asSchemas()
          .createSchema(
              GOVERNED_SCHEMA_NAME, "governed atomic integrity acceptance", new HashMap<>());
      catalog
          .asSchemas()
          .loadSchema(GOVERNED_SCHEMA_NAME)
          .supportsPolicies()
          .associatePolicies(new String[] {POLICY_NAME}, null);
    }
  }

  private void createJdbcCatalog(
      CatalogTarget target,
      boolean kmsBound,
      boolean serverCacheDisabled,
      Map<String, String> extraProperties) {
    Engine engine = target.engine();
    Map<String, String> properties = new HashMap<>();
    properties.put(IcebergConstants.URI, engine.jdbcUrl(target.database()));
    properties.put(IcebergConstants.CATALOG_BACKEND, "jdbc");
    properties.put(IcebergConstants.CATALOG_BACKEND_NAME, target.backendName());
    properties.put(IcebergConstants.GRAVITINO_JDBC_DRIVER, engine.driverClassName);
    properties.put(IcebergConstants.GRAVITINO_JDBC_USER, engine.user());
    properties.put(IcebergConstants.GRAVITINO_JDBC_PASSWORD, engine.password());
    properties.put("gravitino.bypass.jdbc.schema-version", "v1");
    properties.put(IcebergConstants.ICEBERG_JDBC_INITIALIZE, "true");
    // Catalogs sharing one database keep their files apart as well as their rows.
    properties.put(IcebergConstants.WAREHOUSE, "file:///tmp/" + target.name() + "/");
    if (kmsBound) {
      properties.put(IcebergConstants.ENCRYPTION_KMS_SOURCE, RecordingKmsClientFactory.PROVIDER);
    }
    if (serverCacheDisabled) {
      properties.put(IcebergConstants.TABLE_METADATA_CACHE_IMPL, "");
    }
    properties.putAll(extraProperties);
    metalakeClientWithAllPrivilege.createCatalog(
        target.name(), Catalog.Type.RELATIONAL, "lakehouse-iceberg", "comment", properties);
  }

  /**
   * Creates upstream Iceberg's own catalog tables and nothing else, so the catalog created over
   * them finds a supported database whose integrity store is missing.
   */
  private static void provisionUpstreamCatalogTablesOnly(CatalogTarget target) {
    Engine engine = target.engine();
    JdbcCatalog upstream = new JdbcCatalog();
    try {
      upstream.setConf(new Configuration());
      upstream.initialize(
          target.backendName(),
          Map.of(
              CatalogProperties.URI,
              engine.jdbcUrl(target.database()),
              "jdbc.user",
              engine.user(),
              "jdbc.password",
              engine.password(),
              "jdbc.schema-version",
              "V1",
              CatalogProperties.WAREHOUSE_LOCATION,
              "file:///tmp/" + target.name() + "/"));
    } finally {
      upstream.close();
    }
  }

  private RESTCatalog restCatalog(CatalogTarget target) {
    return restCatalog(target.name());
  }

  private RESTCatalog restCatalog(String catalogName) {
    return restCatalogs.computeIfAbsent(
        catalogName,
        name -> {
          Map<String, String> properties = new HashMap<>();
          properties.put(CatalogProperties.URI, getIcebergRestServiceUri());
          // The warehouse selects the named IRC catalog, which becomes the request prefix.
          properties.put(CatalogProperties.WAREHOUSE_LOCATION, name);
          properties.put(CatalogProperties.CACHE_ENABLED, "false");
          properties.put("rest.auth.type", "basic");
          properties.put("rest.auth.basic.username", SUPER_USER);
          properties.put("rest.auth.basic.password", "mock");
          RESTCatalog catalog = new RESTCatalog();
          catalog.setConf(new Configuration());
          catalog.initialize("admin_" + name, properties);
          return catalog;
        });
  }

  private TableCatalog gravitinoTables(CatalogTarget target) {
    return gravitinoTables(target.name());
  }

  private TableCatalog gravitinoTables(String catalogName) {
    if (GRAVITINO_CATALOG_NAME.equals(catalogName)) {
      return catalogClientWithAllPrivilege.asTableCatalog();
    }
    return metalakeClientWithAllPrivilege.loadCatalog(catalogName).asTableCatalog();
  }

  private static TableIdentifier icebergIdentifier(String schemaName, String tableName) {
    return TableIdentifier.of(Namespace.of(schemaName), tableName);
  }

  private static Schema recordSchema() {
    return new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id"));
  }

  private Table createIrcTable(CatalogTarget target, TableIdentifier identifier, String keyId) {
    return createIrcTable(target, identifier, keyId, "3");
  }

  /**
   * Creates a table through the IRC.
   *
   * @param formatVersion the version the request names, or {@code null} to name none
   */
  private Table createIrcTable(
      CatalogTarget target, TableIdentifier identifier, String keyId, String formatVersion) {
    TableBuilder builder =
        restCatalog(target)
            .buildTable(identifier, recordSchema())
            .withProperty(TableProperties.METADATA_COMPRESSION, "none");
    if (formatVersion != null) {
      builder.withProperty(TableProperties.FORMAT_VERSION, formatVersion);
    }
    if (keyId != null) {
      builder.withProperty(TableProperties.ENCRYPTION_TABLE_KEY, keyId);
    }
    return builder.create();
  }

  /**
   * Creates a table through the Gravitino API.
   *
   * @param formatVersion the version the request names, or {@code null} to name none
   */
  private void createVersionedGravitinoTable(
      CatalogTarget target, String tableName, String keyId, String formatVersion) {
    Map<String, String> properties = new HashMap<>();
    if (formatVersion != null) {
      properties.put(TableProperties.FORMAT_VERSION, formatVersion);
    }
    if (keyId != null) {
      properties.put(
          IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER,
          RecordingKmsClientFactory.PROVIDER);
      properties.put(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, keyId);
    }
    Column id = Column.of("id", org.apache.gravitino.rel.types.Types.LongType.get(), "record id");
    gravitinoTables(target)
        .createTable(
            NameIdentifier.of(SCHEMA_NAME, tableName),
            new Column[] {id},
            "format version acceptance",
            properties);
  }

  /**
   * Asserts a create is refused through the given API with the refusal a format version encryption
   * cannot use gets, before the KMS, and that nothing was created.
   *
   * <p>Both APIs raise the same type: the IRC reports the refusal as an {@code
   * IllegalArgumentException}, which the Iceberg REST client reconstructs as itself rather than as
   * a generic bad request.
   */
  private void assertUnsupportedFormatVersion(
      CatalogTarget target, Api api, String tableName, String keyId, String formatVersion) {
    RecordingKmsClientFactory.reset();
    IllegalArgumentException failure =
        api == Api.IRC
            ? Assertions.assertThrowsExactly(
                IllegalArgumentException.class,
                () ->
                    createIrcTable(
                        target, icebergIdentifier(SCHEMA_NAME, tableName), keyId, formatVersion))
            : Assertions.assertThrowsExactly(
                IllegalArgumentException.class,
                () -> createVersionedGravitinoTable(target, tableName, keyId, formatVersion));
    assertNamesTheRequestedVersion(failure);
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
    assertTableAbsent(target.name(), SCHEMA_NAME, tableName);
  }

  /**
   * Asserts a refusal of a version the request named points the client at the request, and not the
   * operator at the catalog default: the request's own value outranks that default on every path,
   * so raising it would change nothing.
   */
  private static void assertNamesTheRequestedVersion(Exception failure) {
    String message = failure.getMessage();
    Assertions.assertTrue(
        message.contains(IcebergFormatVersionResolution.Source.REQUEST.token()),
        () -> "Unexpected format version refusal: " + message);
    Assertions.assertTrue(
        message.contains(
            "format-version=" + IcebergFormatVersionResolution.MIN_ENCRYPTED_FORMAT_VERSION),
        () -> "Unexpected format version refusal: " + message);
    Assertions.assertFalse(
        message.contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION),
        () -> "Unexpected format version refusal: " + message);
  }

  /** Asserts a refusal points the operator at the catalog property that would settle it. */
  private static void assertNamesTheCatalogDefaultVersion(Exception failure) {
    Assertions.assertTrue(
        failure.getMessage().contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION),
        () -> "Unexpected format version refusal: " + failure.getMessage());
  }

  private int ircFormatVersion(CatalogTarget target, String tableName) {
    return ((BaseTable) restCatalog(target).loadTable(icebergIdentifier(SCHEMA_NAME, tableName)))
        .operations()
        .current()
        .formatVersion();
  }

  private void createGravitinoTable(
      CatalogTarget target, String schemaName, String tableName, String provider, String keyId) {
    createGravitinoTable(target.name(), schemaName, tableName, provider, keyId);
  }

  private void createGravitinoTable(
      String catalogName, String schemaName, String tableName, String provider, String keyId) {
    Map<String, String> properties = new HashMap<>();
    properties.put(TableProperties.FORMAT_VERSION, "3");
    if (provider != null) {
      properties.put(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, provider);
    }
    if (keyId != null) {
      properties.put(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, keyId);
    }
    Column id = Column.of("id", org.apache.gravitino.rel.types.Types.LongType.get(), "record id");
    gravitinoTables(catalogName)
        .createTable(
            NameIdentifier.of(schemaName, tableName),
            new Column[] {id},
            "atomic integrity acceptance",
            properties);
  }

  private RegistrationVersions prepareRegistrationVersions(
      CatalogTarget target, TableIdentifier identifier, String keyId) {
    BaseTable table = (BaseTable) createIrcTable(target, identifier, keyId);
    TableMetadata created = table.operations().current();
    table.updateProperties().set(REVISION_PROPERTY, "2").commit();
    TableMetadata updated = table.operations().current();
    Assertions.assertTrue(restCatalog(target).dropTable(identifier, false));
    return new RegistrationVersions(created, updated);
  }

  /** Creates a table only for its metadata file, then drops it without purge. */
  private TableMetadata createAndDropDonor(CatalogTarget target, String tableName, String keyId) {
    TableIdentifier identifier = icebergIdentifier(SCHEMA_NAME, tableName);
    TableMetadata metadata =
        ((BaseTable) createIrcTable(target, identifier, keyId)).operations().current();
    Assertions.assertTrue(restCatalog(target).dropTable(identifier, false));
    return metadata;
  }

  private void assertRegisterOverwriteRejectedWith400(
      CatalogTarget target, String tableName, TableMetadata replacement) throws SQLException {
    Pointer pointerBefore =
        loadPointer(target, SCHEMA_NAME, tableName).orElseThrow(AssertionError::new);
    int integrityRowsBefore = countIntegrityRows(target, SCHEMA_NAME, tableName);
    TrustedRow rowBefore =
        integrityRowsBefore == 0 ? null : loadTrustedRow(target, SCHEMA_NAME, tableName);

    // Type only (F-7): exactly the type this 400 raises client-side, so a 412 cannot pass. The
    // repoint guard throws IllegalArgumentException (Q-name), which the server reports as a 400
    // of that type, and Iceberg's ErrorHandlers.DefaultErrorHandler turns a 400 typed
    // IllegalArgumentException back into a plain IllegalArgumentException rather than a
    // BadRequestException. A 412 surfaces as RESTException, so exactness still rules it out.
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            restCatalog(target)
                .registerTable(
                    icebergIdentifier(SCHEMA_NAME, tableName),
                    replacement.metadataFileLocation(),
                    true));

    Assertions.assertEquals(
        pointerBefore,
        loadPointer(target, SCHEMA_NAME, tableName).orElseThrow(AssertionError::new));
    Assertions.assertEquals(
        integrityRowsBefore, countIntegrityRows(target, SCHEMA_NAME, tableName));
    if (rowBefore != null) {
      assertTrustedRowsEqual(rowBefore, loadTrustedRow(target, SCHEMA_NAME, tableName));
    }
  }

  private void assertRowsMoved(CatalogTarget target, String from, String to, TrustedRow expected)
      throws SQLException {
    assertNoRows(target, SCHEMA_NAME, from);
    Assertions.assertEquals(1, countPointerRows(target, SCHEMA_NAME, to));
    Assertions.assertEquals(1, countIntegrityRows(target, SCHEMA_NAME, to));
    assertTrustedRowsEqual(expected, loadTrustedRow(target, SCHEMA_NAME, to));
  }

  private void assertNoRows(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    Assertions.assertEquals(0, countPointerRows(target, schemaName, tableName));
    Assertions.assertEquals(0, countIntegrityRows(target, schemaName, tableName));
  }

  private void assertNoIntegrityRow(CatalogTarget target, String tableName) throws SQLException {
    Assertions.assertEquals(0, countIntegrityRows(target, SCHEMA_NAME, tableName));
  }

  private void assertPointerAt(CatalogTarget target, String tableName, String expectedLocation)
      throws SQLException {
    Assertions.assertEquals(
        expectedLocation,
        loadPointer(target, SCHEMA_NAME, tableName)
            .orElseThrow(AssertionError::new)
            .metadataLocation());
  }

  private void assertTableAbsent(String catalogName, String schemaName, String tableName) {
    NameIdentifier gravitinoIdentifier = NameIdentifier.of(schemaName, tableName);
    Assertions.assertFalse(gravitinoTables(catalogName).tableExists(gravitinoIdentifier));
    Assertions.assertThrows(
        org.apache.gravitino.exceptions.NoSuchTableException.class,
        () -> gravitinoTables(catalogName).loadTable(gravitinoIdentifier));
    Assertions.assertThrows(
        NoSuchTableException.class,
        () -> restCatalog(catalogName).loadTable(icebergIdentifier(schemaName, tableName)));
  }

  private void assertKeyVisibleOnBothApis(
      CatalogTarget target, TableIdentifier icebergIdentifier, NameIdentifier gravitinoIdentifier) {
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        restCatalog(target)
            .loadTable(icebergIdentifier)
            .properties()
            .get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        gravitinoTables(target)
            .loadTable(gravitinoIdentifier)
            .properties()
            .get(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  private void assertIntegrityFailureAcrossApis(
      CatalogTarget target,
      TableIdentifier icebergIdentifier,
      NameIdentifier gravitinoIdentifier,
      String neverEchoed) {
    // Only the two refusals below may be audited from here on; every earlier load succeeded.
    CapturingAuditLogWriter.clear();
    RESTException ircFailure =
        Assertions.assertThrowsExactly(
            RESTException.class, () -> restCatalog(target).loadTable(icebergIdentifier));
    Assertions.assertEquals(
        String.format(
            "Unable to process (code: 412, type: %s): %s",
            MetadataIntegrityException.class.getSimpleName(),
            ICEBERG_METADATA_INTEGRITY_PUBLIC_MESSAGE),
        ircFailure.getMessage());

    MetadataIntegrityException gravitinoFailure =
        Assertions.assertThrows(
            MetadataIntegrityException.class,
            () -> gravitinoTables(target).loadTable(gravitinoIdentifier));
    Assertions.assertEquals(
        GRAVITINO_METADATA_INTEGRITY_PUBLIC_MESSAGE, gravitinoFailure.getMessage());

    if (neverEchoed != null) {
      Assertions.assertFalse(ircFailure.getMessage().contains(neverEchoed));
      Assertions.assertFalse(gravitinoFailure.getMessage().contains(neverEchoed));
    }

    assertIntegrityRefusalAudited(
        IcebergEncryptionAuditInfos.Reason.METADATA_INTEGRITY_FAILED,
        MetadataIntegrityException.class,
        IcebergCatalogBackend.JDBC);
  }

  private static void assertIrcIntegrityFailure(Executable executable) {
    RESTException failure = Assertions.assertThrowsExactly(RESTException.class, executable);
    Assertions.assertTrue(
        failure.getMessage().startsWith(IRC_INTEGRITY_FAILURE_PREFIX),
        () -> "Expected a 412 integrity failure, got: " + failure.getMessage());
  }

  /**
   * Asserts that the two refusals just observed each reached the audit log as one {@code
   * LOAD_TABLE} failure line, one from the Iceberg REST server and one from the Gravitino server,
   * carrying the facts the failure classifier names structurally: the reason, the exception class,
   * the backend, that the catalog is governed, and its atomic-integrity capability.
   *
   * <p>The audit listener is asynchronous, so the lines are awaited. Nothing here passes on an
   * empty log: a refusal that never produced a line fails on the timeout, and the message carries
   * whatever lines did arrive.
   */
  private static void assertIntegrityRefusalAudited(
      IcebergEncryptionAuditInfos.Reason reason,
      Class<? extends Exception> errorType,
      IcebergCatalogBackend backend) {
    Awaitility.await("one audited LOAD_TABLE failure per API")
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                Assertions.assertEquals(
                    2,
                    loadTableFailureLines().size(),
                    () -> "audit lines written: " + CapturingAuditLogWriter.lines()));

    List<WrittenAuditLine> lines = loadTableFailureLines();
    Assertions.assertEquals(
        Set.of(
            EventSource.GRAVITINO_ICEBERG_REST_SERVER.name(), EventSource.GRAVITINO_SERVER.name()),
        lines.stream()
            .map(IcebergEncryptionAtomicIntegrityIT::eventSource)
            .collect(Collectors.toSet()),
        () -> "one line per API: " + rawLines(lines));
    for (WrittenAuditLine line : lines) {
      Map<String, String> facts = line.customInfo();
      Assertions.assertEquals(
          reason.code(), facts.get(IcebergEncryptionAuditInfos.REASON), line.raw());
      Assertions.assertEquals(
          errorType.getName(), facts.get(IcebergEncryptionAuditInfos.ERROR_TYPE), line.raw());
      Assertions.assertEquals(
          backend.name(), facts.get(IcebergEncryptionAuditInfos.BACKEND), line.raw());
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

  /**
   * Pins the fixture state this test is about: the database carries upstream Iceberg's own catalog
   * tables and no integrity store, so the store probe fails and the backend reports unsupported.
   * Asserted up front because the failure is otherwise mute: the container helpers swallow "already
   * exists", and a database that arrives carrying a store instead makes the backend supported,
   * which pushes the refusals below onto the policy evaluator and reports a key-source mismatch
   * that says nothing about the leaked table.
   */
  private static void assertPlainUpstreamDatabase(CatalogTarget target) throws SQLException {
    try (Connection connection = connect(target)) {
      Assertions.assertTrue(
          hasTable(connection, "iceberg_tables"),
          () -> "Upstream Iceberg's catalog tables are missing from " + target.database() + ".");
      Assertions.assertFalse(
          hasTable(connection, "iceberg_metadata_integrity"),
          () ->
              "An integrity store leaked into "
                  + target.database()
                  + ", which makes the backend supported and leaves this test no subject.");
    }
  }

  private static boolean hasTable(Connection connection, String tableName) throws SQLException {
    try (ResultSet tables =
        connection.getMetaData().getTables(connection.getCatalog(), null, tableName, null)) {
      return tables.next();
    }
  }

  private static void assertUnsupportedBackend(Exception failure) {
    Assertions.assertTrue(
        failure.getMessage().contains(UNSUPPORTED_BACKEND_PHRASE),
        () -> "Unexpected unsupported-backend message: " + failure.getMessage());
  }

  private static void assertTrustedRow(TrustedRow row) {
    Assertions.assertEquals(row.metadataLocation(), row.trustedMetadataLocation());
    Assertions.assertEquals(RecordingKmsClientFactory.KEY_ID, row.keyId());
    Assertions.assertEquals(IcebergMetadataIntegrity.HASH_ALGORITHM, row.hashAlgorithm());
    try (HadoopFileIO fileIO = new HadoopFileIO(new Configuration())) {
      TableMetadata metadata = TableMetadataParser.read(fileIO, row.metadataLocation());
      Assertions.assertEquals(IcebergMetadataIntegrity.metadataHash(metadata), row.metadataHash());
    }
  }

  private static void assertTrustedRowAdvanced(TrustedRow before, TrustedRow after) {
    Assertions.assertNotEquals(before.metadataLocation(), after.metadataLocation());
    Assertions.assertEquals(before.metadataLocation(), after.previousMetadataLocation());
    Assertions.assertNotEquals(before.metadataHash(), after.metadataHash());
  }

  private static void assertTrustedRowsEqual(TrustedRow expected, TrustedRow actual) {
    Assertions.assertEquals(expected, actual);
  }

  private static void assertIntegrityColumnsEqual(TrustedRow expected, TrustedRow actual) {
    Assertions.assertEquals(expected.trustedMetadataLocation(), actual.trustedMetadataLocation());
    Assertions.assertEquals(expected.keyId(), actual.keyId());
    Assertions.assertEquals(expected.hashAlgorithm(), actual.hashAlgorithm());
    Assertions.assertEquals(expected.metadataHash(), actual.metadataHash());
  }

  private static void assertLastKmsReferenceIsTheAllowedKey() {
    Assertions.assertEquals(
        new KmsReference(RecordingKmsClientFactory.PROVIDER, RecordingKmsClientFactory.KEY_ID),
        RecordingKmsClientFactory.lastReference().orElseThrow(AssertionError::new));
  }

  /**
   * Evicts the table from both server-side metadata caches: the IRC catalog wrapper's, and the one
   * inside the Gravitino catalog's operations, which the IRC create warmed when it imported the
   * table and which lives behind the catalog class loader.
   */
  private void evictServerCaches(CatalogTarget target, String schemaName, String tableName)
      throws Exception {
    CatalogWrapperForREST ircWrapper =
        IcebergRESTServerContext.getInstance()
            .catalogWrapperManager()
            .getCatalogWrapper(target.name());
    TableMetadataCache ircCache =
        (TableMetadataCache) FieldUtils.readField(ircWrapper, "metadataCache", true);
    if (ircCache != null) {
      ircCache.invalidate(icebergIdentifier(schemaName, tableName));
    }

    GravitinoEnv.getInstance()
        .catalogManager()
        .doWithCatalog(
            NameIdentifier.of(METALAKE_NAME, target.name()),
            catalog -> {
              Object wrapper = FieldUtils.readField(catalog.ops(), "icebergCatalogWrapper", true);
              Object cache = FieldUtils.readField(wrapper, "metadataCache", true);
              if (cache != null) {
                Class<?> identifierClass =
                    cache.getClass().getClassLoader().loadClass(TableIdentifier.class.getName());
                Object identifier =
                    identifierClass
                        .getMethod("of", String[].class)
                        .invoke(null, (Object) new String[] {schemaName, tableName});
                MethodUtils.invokeMethod(cache, true, "invalidate", identifier);
              }
              return null;
            });
  }

  private HttpFailure postUpdateTable(
      String catalogName, String schemaName, String tableName, UpdateTableRequest request)
      throws IOException {
    URI uri =
        URI.create(
            String.format(
                "%sv1/%s/namespaces/%s/tables/%s",
                getIcebergRestServiceUri(),
                RESTUtil.encodeString(catalogName),
                RESTUtil.encodeString(schemaName),
                RESTUtil.encodeString(tableName)));
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setRequestProperty(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((SUPER_USER + ":mock").getBytes(StandardCharsets.UTF_8)));
    try (OutputStream output = connection.getOutputStream()) {
      output.write(UpdateTableRequestParser.toJson(request).getBytes(StandardCharsets.UTF_8));
    }
    int status = connection.getResponseCode();
    String type = null;
    try (InputStream error = connection.getErrorStream()) {
      if (error != null) {
        type = OBJECT_MAPPER.readTree(error).path("error").path("type").asText(null);
      }
    } finally {
      connection.disconnect();
    }
    return new HttpFailure(status, type);
  }

  private TrustedRow loadTrustedRow(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    String sql =
        "SELECT t.metadata_location, t.previous_metadata_location, "
            + "i.metadata_location AS trusted_metadata_location, i.table_key_id, "
            + "i.hash_algorithm, i.metadata_hash FROM iceberg_tables t "
            + "JOIN iceberg_metadata_integrity i ON i.catalog_name = t.catalog_name "
            + "AND i.table_namespace = t.table_namespace AND i.table_name = t.table_name "
            + "WHERE t.catalog_name = ? AND t.table_namespace = ? AND t.table_name = ?";
    try (Connection connection = connect(target);
        PreparedStatement statement =
            identityStatement(connection, sql, target, schemaName, tableName);
        ResultSet rows = statement.executeQuery()) {
      Assertions.assertTrue(rows.next());
      TrustedRow row =
          new TrustedRow(
              rows.getString("metadata_location"),
              rows.getString("previous_metadata_location"),
              rows.getString("trusted_metadata_location"),
              rows.getString("table_key_id"),
              rows.getString("hash_algorithm"),
              rows.getString("metadata_hash"));
      Assertions.assertFalse(rows.next());
      return row;
    }
  }

  private Optional<Pointer> loadPointer(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    String sql =
        "SELECT metadata_location, previous_metadata_location FROM iceberg_tables "
            + "WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?";
    try (Connection connection = connect(target);
        PreparedStatement statement =
            identityStatement(connection, sql, target, schemaName, tableName);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return Optional.empty();
      }
      return Optional.of(
          new Pointer(
              rows.getString("metadata_location"), rows.getString("previous_metadata_location")));
    }
  }

  private int countPointerRows(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    return countRows(
        target,
        "SELECT COUNT(*) FROM iceberg_tables "
            + "WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?",
        schemaName,
        tableName);
  }

  private int countIntegrityRows(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    return countRows(
        target,
        "SELECT COUNT(*) FROM iceberg_metadata_integrity "
            + "WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?",
        schemaName,
        tableName);
  }

  private int countRows(CatalogTarget target, String sql, String schemaName, String tableName)
      throws SQLException {
    try (Connection connection = connect(target);
        PreparedStatement statement =
            identityStatement(connection, sql, target, schemaName, tableName);
        ResultSet rows = statement.executeQuery()) {
      Assertions.assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  private void tamperTrustedHash(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    updateIntegrityColumn(target, schemaName, tableName, "metadata_hash", TAMPERED_HASH);
  }

  private void updateIntegrityColumn(
      CatalogTarget target, String schemaName, String tableName, String column, String value)
      throws SQLException {
    updateSingleRow(
        target,
        "UPDATE iceberg_metadata_integrity SET "
            + column
            + " = ? WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?",
        value,
        schemaName,
        tableName);
  }

  private void updatePointer(
      CatalogTarget target, String schemaName, String tableName, String metadataLocation)
      throws SQLException {
    updateSingleRow(
        target,
        "UPDATE iceberg_tables SET metadata_location = ? "
            + "WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?",
        metadataLocation,
        schemaName,
        tableName);
  }

  private void deleteIntegrityRow(CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    String sql =
        "DELETE FROM iceberg_metadata_integrity "
            + "WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?";
    try (Connection connection = connect(target);
        PreparedStatement statement =
            identityStatement(connection, sql, target, schemaName, tableName)) {
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  private void updateSingleRow(
      CatalogTarget target, String sql, String value, String schemaName, String tableName)
      throws SQLException {
    try (Connection connection = connect(target);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      statement.setString(2, target.backendName());
      statement.setString(3, schemaName);
      statement.setString(4, tableName);
      Assertions.assertEquals(1, statement.executeUpdate());
    }
  }

  /**
   * Makes the backend reject integrity writes for one table identity, per dialect: a PostgreSQL
   * check constraint that skips existing rows, or MySQL triggers that signal on insert and update.
   */
  private void addRejectIntegrityWrite(
      CatalogTarget target, String rejectionName, String schemaName, String tableName)
      throws SQLException {
    if (target.engine() == Engine.POSTGRESQL) {
      execute(
          target,
          String.format(
              "ALTER TABLE iceberg_metadata_integrity ADD CONSTRAINT %s CHECK (NOT (%s)) NOT VALID",
              quotePostgresIdentifier(rejectionName),
              identityPredicate("", target, schemaName, tableName)));
      return;
    }
    for (String event : List.of("INSERT", "UPDATE")) {
      execute(
          target,
          String.format(
              "CREATE TRIGGER %s BEFORE %s ON iceberg_metadata_integrity FOR EACH ROW "
                  + "BEGIN IF %s THEN SIGNAL SQLSTATE '45000' "
                  + "SET MESSAGE_TEXT = 'integrity write rejected by acceptance test'; END IF; END",
              quoteMysqlIdentifier(mysqlTriggerName(rejectionName, event)),
              event,
              identityPredicate("NEW.", target, schemaName, tableName)));
    }
  }

  private void removeRejectIntegrityWrite(CatalogTarget target, String rejectionName)
      throws SQLException {
    if (target.engine() == Engine.POSTGRESQL) {
      execute(
          target,
          "ALTER TABLE iceberg_metadata_integrity DROP CONSTRAINT IF EXISTS "
              + quotePostgresIdentifier(rejectionName));
      return;
    }
    for (String event : List.of("INSERT", "UPDATE")) {
      execute(
          target,
          "DROP TRIGGER IF EXISTS " + quoteMysqlIdentifier(mysqlTriggerName(rejectionName, event)));
    }
  }

  private static String mysqlTriggerName(String rejectionName, String event) {
    return rejectionName + "_" + event.toLowerCase();
  }

  private static String identityPredicate(
      String columnPrefix, CatalogTarget target, String schemaName, String tableName) {
    return String.format(
        "%1$scatalog_name = '%2$s' AND %1$stable_namespace = '%3$s' AND %1$stable_name = '%4$s'",
        columnPrefix,
        quoteLiteral(target.backendName()),
        quoteLiteral(schemaName),
        quoteLiteral(tableName));
  }

  private void execute(CatalogTarget target, String sql) throws SQLException {
    try (Connection connection = connect(target);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Connection connect(CatalogTarget target) throws SQLException {
    Engine engine = target.engine();
    return DriverManager.getConnection(
        engine.jdbcUrl(target.database()), engine.user(), engine.password());
  }

  private static PreparedStatement identityStatement(
      Connection connection, String sql, CatalogTarget target, String schemaName, String tableName)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setString(1, target.backendName());
    statement.setString(2, schemaName);
    statement.setString(3, tableName);
    return statement;
  }

  private static String quotePostgresIdentifier(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  private static String quoteMysqlIdentifier(String identifier) {
    return '`' + identifier.replace("`", "``") + '`';
  }

  private static String quoteLiteral(String value) {
    return value.replace("'", "''");
  }

  private static void tamperMetadata(String metadataLocation) throws IOException {
    URI metadataUri = URI.create(metadataLocation);
    Path metadataPath = new Path(metadataUri);
    try (FileSystem fileSystem = FileSystem.newInstance(metadataUri, new Configuration())) {
      JsonNode metadata;
      try (FSDataInputStream input = fileSystem.open(metadataPath)) {
        metadata = OBJECT_MAPPER.readTree(input);
      }
      ObjectNode properties = (ObjectNode) metadata.get("properties");
      Assertions.assertNotNull(properties);
      properties.put("acceptance.tampered", "true");
      try (FSDataOutputStream output = fileSystem.create(metadataPath, true)) {
        output.write(OBJECT_MAPPER.writeValueAsBytes(metadata));
      }
    }
  }

  private static void deleteFile(String location) throws IOException {
    URI uri = URI.create(location);
    try (FileSystem fileSystem = FileSystem.newInstance(uri, new Configuration())) {
      Assertions.assertTrue(fileSystem.delete(new Path(uri), false));
    }
  }

  /** The database engine a JDBC catalog runs on. */
  enum Engine {
    POSTGRESQL(
        GRAVITINO_CATALOG_NAME,
        TestDatabaseName.PG_ICEBERG_AUTHZ_IT,
        TestDatabaseName.PG_ICEBERG_INTEGRITY_PLAIN_MODE_IT,
        "org.postgresql.Driver"),
    MYSQL(
        "iceberg_mysql",
        TestDatabaseName.MYSQL_ICEBERG_INTEGRITY_IT,
        TestDatabaseName.MYSQL_ICEBERG_INTEGRITY_PLAIN_MODE_IT,
        "com.mysql.cj.jdbc.Driver");

    private final String catalogName;
    private final TestDatabaseName database;
    private final TestDatabaseName plainModeDatabase;
    private final String driverClassName;

    Engine(
        String catalogName,
        TestDatabaseName database,
        TestDatabaseName plainModeDatabase,
        String driverClassName) {
      this.catalogName = catalogName;
      this.database = database;
      this.plainModeDatabase = plainModeDatabase;
      this.driverClassName = driverClassName;
    }

    String jdbcUrl(TestDatabaseName databaseName) {
      if (this == POSTGRESQL) {
        return CONTAINER_SUITE.getPostgreSQLContainer().getJdbcUrl(databaseName);
      }
      // Scopes MySQL metadata lookups to this database; several databases share the server.
      return CONTAINER_SUITE.getMySQLContainer().getJdbcUrl(databaseName)
          + "?nullCatalogMeansCurrent=true";
    }

    String user() {
      return this == POSTGRESQL
          ? CONTAINER_SUITE.getPostgreSQLContainer().getUsername()
          : CONTAINER_SUITE.getMySQLContainer().getUsername();
    }

    String password() {
      return this == POSTGRESQL
          ? CONTAINER_SUITE.getPostgreSQLContainer().getPassword()
          : CONTAINER_SUITE.getMySQLContainer().getPassword();
    }
  }

  /** How a keyed catalog binds its key to every table it creates. */
  enum CatalogKeyMode {
    TABLE_OVERRIDE("iceberg_key_override", "table-override." + IcebergConstants.ENCRYPTION_KEY_ID),
    TABLE_DEFAULT("iceberg_key_default", "table-default." + IcebergConstants.ENCRYPTION_KEY_ID);

    private final String catalogName;
    private final String catalogProperty;

    CatalogKeyMode(String catalogName, String catalogProperty) {
      this.catalogName = catalogName;
      this.catalogProperty = catalogProperty;
    }
  }

  /** The API a request arrives through. */
  enum Api {
    IRC,
    GRAVITINO
  }

  /** One governed-create denial and everything both APIs must show for it. */
  enum DenialReason {
    KEY_REQUIRED(null, FailureKind.POLICY_DENIED, IcebergEncryptionDecision.Reason.KEY_REQUIRED, 0),
    KEY_NOT_ALLOWED(
        DISALLOWED_KEY_ID,
        FailureKind.POLICY_DENIED,
        IcebergEncryptionDecision.Reason.KEY_NOT_ALLOWED,
        0),
    KMS_KEY_INVALID(
        RecordingKmsClientFactory.MISSING_KEY_ID,
        FailureKind.KMS_KEY_INVALID,
        IcebergEncryptionDecision.Reason.KMS_KEY_INVALID,
        1),
    KMS_UNAVAILABLE(
        RecordingKmsClientFactory.UNAVAILABLE_KEY_ID,
        FailureKind.KMS_UNAVAILABLE,
        IcebergEncryptionDecision.Reason.KMS_SERVICE_UNAVAILABLE,
        1);

    private final String keyId;
    private final FailureKind kind;
    private final IcebergEncryptionDecision.Reason decisionReason;
    // The allow list is checked before the KMS, so only KMS outcomes cost a call.
    private final int kmsInspections;

    DenialReason(
        String keyId,
        FailureKind kind,
        IcebergEncryptionDecision.Reason decisionReason,
        int kmsInspections) {
      this.keyId = keyId;
      this.kind = kind;
      this.decisionReason = decisionReason;
      this.kmsInspections = kmsInspections;
    }

    String keyId() {
      return keyId;
    }

    FailureKind kind() {
      return kind;
    }

    IcebergEncryptionDecision.Reason decisionReason() {
      return decisionReason;
    }

    int kmsInspections() {
      return kmsInspections;
    }

    Class<? extends RuntimeException> ircType() {
      return kind == FailureKind.KMS_UNAVAILABLE
          ? ServiceUnavailableException.class
          : BadRequestException.class;
    }

    Class<? extends RuntimeException> gravitinoType() {
      return kind == FailureKind.KMS_UNAVAILABLE
          ? ConnectionFailedException.class
          : IllegalArgumentException.class;
    }
  }

  /** A catalog and where its pointer and integrity rows live. */
  record CatalogTarget(String name, String backendName, Engine engine, TestDatabaseName database) {

    static CatalogTarget base(Engine engine) {
      return new CatalogTarget(engine.catalogName, "jdbc", engine, engine.database);
    }

    static CatalogTarget cached(Engine engine) {
      return new CatalogTarget(
          engine.catalogName + "_cached", "jdbc_cached", engine, engine.database);
    }

    static CatalogTarget plainMode(Engine engine) {
      return new CatalogTarget(
          "plain_mode_" + engine.name().toLowerCase(),
          "jdbc_plain_mode",
          engine,
          engine.plainModeDatabase);
    }

    /** A catalog binding its key the given way on the given engine. */
    static CatalogTarget keyed(CatalogKeyMode mode, Engine engine) {
      String name = mode.catalogName + "_" + engine.name().toLowerCase();
      return new CatalogTarget(name, "jdbc_" + name, engine, engine.database);
    }

    /** An unkeyed catalog whose {@code table-default.format-version} is 3. */
    static CatalogTarget defaultThree(Engine engine) {
      return named("iceberg_default_three_" + engine.name().toLowerCase(), engine);
    }

    /** A catalog created without a key, which one test binds a default key to later. */
    static CatalogTarget keyedLater(Engine engine) {
      return named("iceberg_keyed_later_" + engine.name().toLowerCase(), engine);
    }

    private static CatalogTarget named(String name, Engine engine) {
      return new CatalogTarget(name, "jdbc_" + name, engine, engine.database);
    }
  }

  record TrustedRow(
      String metadataLocation,
      String previousMetadataLocation,
      String trustedMetadataLocation,
      String keyId,
      String hashAlgorithm,
      String metadataHash) {}

  record Pointer(String metadataLocation, String previousMetadataLocation) {}

  record RegistrationVersions(TableMetadata created, TableMetadata updated) {}

  record HttpFailure(int status, String type) {}
}
