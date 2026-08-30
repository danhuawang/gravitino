/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.iceberg.service;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.encryption.IcebergEncryptionDecision.KmsValidationStatus;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProviderFactory;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Pins how the Iceberg REST catalog wrapper treats KMS key confirmation, including the HTTP status
 * each outcome maps to.
 *
 * <p>Confirmation is opt-in per catalog through {@code encryption-kms-source}. A catalog that does
 * not opt in keeps passing Iceberg's native {@code encryption.key-id} straight through, so enabling
 * this feature elsewhere cannot regress plain Iceberg encryption. A catalog that does opt in fails
 * closed: an unusable key is caller error (400) and a KMS that cannot answer, including one that
 * was never wired up, is retriable (503). Neither rejection leaves a table behind, and plaintext
 * creates are unaffected either way.
 *
 * <p>The same outcomes are pinned on the two paths that reach this behavior in production: wrappers
 * built by {@link IcebergCatalogWrapperManager}, which decides whether a catalog gets a validator
 * at all, and {@link FederatedCatalogWrapper}, whose {@code createTable} override bypasses the base
 * implementation and so has to confirm keys itself.
 */
public class TestCatalogWrapperForRESTEncryptionKeys {

  private static final String KMS_SOURCE = "corp-kms";

  /**
   * A remote no test listens on. Federated calls that reach the remote catalog therefore fail,
   * which is what lets these tests distinguish "rejected before forwarding" from "rejected
   * downstream".
   */
  private static final String UNREACHABLE_REMOTE = "http://localhost:1";

  private static final Namespace NAMESPACE = Namespace.of("db");
  private static final TableIdentifier TABLE = TableIdentifier.of(NAMESPACE, "tbl");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));

  @TempDir private Path warehouse;

  /** Records every reference it is asked about so tests can assert on what the wrapper passed. */
  private static final class RecordingValidator
      implements IcebergEncryptionPolicyEvaluator.KmsKeyValidator {

    private final List<KmsReference> seen = new ArrayList<>();
    private final KmsValidationStatus status;

    private RecordingValidator(KmsValidationStatus status) {
      this.status = status;
    }

    @Override
    public KmsValidationStatus validate(KmsReference key) {
      seen.add(key);
      return status;
    }
  }

  /**
   * Clears the server components installed by the manager tests so a test that expects an
   * uninitialized {@link GravitinoEnv} cannot pass on state left behind by an earlier one.
   */
  @AfterEach
  void clearServerComponents() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", null, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "kmsClientRegistry", null, true);
  }

  @Test
  void testCreateConfirmsRequestedKeyAgainstCatalogKmsSource() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.VALID);
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE, validator);

    LoadTableResponse response = wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    Assertions.assertEquals(1, validator.seen.size());
    // The provider comes from the catalog binding and the key ID from the request, so a caller
    // cannot aim a catalog at a different KMS by naming one in table properties.
    Assertions.assertEquals(KMS_SOURCE, validator.seen.get(0).provider());
    Assertions.assertEquals("k-1", validator.seen.get(0).keyId());
    Assertions.assertEquals(
        "k-1", response.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testCreateRejectsKeyTheKmsReportsUnusable() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.INVALID);
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE, validator);

    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("missing"), false));

    Assertions.assertTrue(e.getMessage().contains("missing"), e.getMessage());
    Assertions.assertTrue(e.getMessage().contains(KMS_SOURCE), e.getMessage());
    // A key the KMS rejects is caller error, not a server fault.
    Assertions.assertEquals(400, IcebergExceptionMapper.getErrorCode(e));
    Assertions.assertFalse(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testCreateFailsClosedWhenTheKmsCannotAnswer() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.UNAVAILABLE);
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE, validator);

    // An unreachable KMS must not degrade into accepting an unconfirmed key binding.
    ServiceUnavailableException e =
        Assertions.assertThrows(
            ServiceUnavailableException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    Assertions.assertTrue(e.getMessage().contains(KMS_SOURCE), e.getMessage());
    Assertions.assertEquals(503, IcebergExceptionMapper.getErrorCode(e));
    Assertions.assertFalse(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testBoundCatalogWithoutKmsClientFailsClosed() {
    // The operator named a KMS this deployment cannot consult. Accepting the create would persist a
    // key binding nothing confirmed, so it fails as retriable rather than succeeding.
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE, null);

    ServiceUnavailableException e =
        Assertions.assertThrows(
            ServiceUnavailableException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    Assertions.assertTrue(e.getMessage().contains(KMS_SOURCE), e.getMessage());
    Assertions.assertEquals(503, IcebergExceptionMapper.getErrorCode(e));
    Assertions.assertFalse(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testPlaintextCreateNeverConsultsTheKms() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.INVALID);
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE, validator);

    wrapper.createTable(NAMESPACE, request(ImmutableMap.of()), false);

    Assertions.assertTrue(validator.seen.isEmpty());
    Assertions.assertTrue(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testBoundCatalogWithoutKmsClientStillAllowsPlaintext() {
    // Opting a catalog in to a KMS must not break unencrypted tables in the same catalog.
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE, null);

    wrapper.createTable(NAMESPACE, request(ImmutableMap.of()), false);

    Assertions.assertTrue(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testUnboundCatalogPassesNativeIcebergEncryptionThrough() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.INVALID);
    // A catalog that opted into no Gravitino KMS may still drive Iceberg's own encryption, so the
    // request must be neither confirmed nor rejected here.
    CatalogWrapperForREST wrapper = wrapper(null, validator);

    LoadTableResponse response = wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    Assertions.assertTrue(validator.seen.isEmpty());
    Assertions.assertEquals(
        "k-1", response.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testEncryptionKeyIdentitySurvivesCreateToLoad() {
    CatalogWrapperForREST wrapper =
        wrapper(KMS_SOURCE, new RecordingValidator(KmsValidationStatus.VALID));
    wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    LoadTableResponse loaded = wrapper.loadTable(TABLE, false, CredentialPrivilege.READ);

    Assertions.assertEquals(
        "k-1", loaded.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testManagerGivesKmsBoundCatalogsAWorkingValidator() throws IllegalAccessException {
    installCatalogManager();
    installKmsClientRegistry(registryThatResolvesNoClient());

    // Going through the manager exercises the real resolution path, so the validator it attaches is
    // the one backed by the server's KMS registry rather than a test double.
    CatalogWrapperForREST wrapper =
        withNamespace(manager(true).createCatalogWrapper("bound", catalogConfig(KMS_SOURCE)));

    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    Assertions.assertEquals(400, IcebergExceptionMapper.getErrorCode(e));
    Assertions.assertFalse(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testManagerKeepsUnboundCatalogsOffServerComponents() throws IllegalAccessException {
    installCatalogManager();

    // The KMS registry is deliberately left uninitialized. GravitinoEnv throws when it is read, so
    // this create succeeds only if the manager checks the catalog binding before reaching for the
    // registry. That ordering is what keeps catalogs using no KMS independent of server internals.
    CatalogWrapperForREST wrapper =
        withNamespace(manager(true).createCatalogWrapper("unbound", catalogConfig(null)));

    LoadTableResponse response = wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    Assertions.assertEquals(
        "k-1", response.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testStandaloneServiceFailsClosedForKmsBoundCatalog() {
    // Outside the Gravitino server there is no KMS registry to consult, so a catalog that names a
    // KMS cannot confirm anything. It must refuse the create rather than accept an unconfirmed key.
    CatalogWrapperForREST wrapper =
        withNamespace(manager(false).createCatalogWrapper("bound", catalogConfig(KMS_SOURCE)));

    ServiceUnavailableException e =
        Assertions.assertThrows(
            ServiceUnavailableException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    Assertions.assertEquals(503, IcebergExceptionMapper.getErrorCode(e));
    Assertions.assertFalse(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testFederatedCreateRejectsKeyTheKmsReportsUnusable() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.INVALID);
    FederatedCatalogWrapper wrapper = federatedWrapper(validator);

    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    // Surfacing our rejection rather than a connection failure against UNREACHABLE_REMOTE shows the
    // federated override confirms the key before forwarding anything to the remote catalog.
    Assertions.assertEquals(400, IcebergExceptionMapper.getErrorCode(e));
    Assertions.assertEquals(1, validator.seen.size());
    Assertions.assertEquals(KMS_SOURCE, validator.seen.get(0).provider());
    Assertions.assertEquals("k-1", validator.seen.get(0).keyId());
  }

  @Test
  void testFederatedCreateFailsClosedWhenTheKmsCannotAnswer() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.UNAVAILABLE);
    FederatedCatalogWrapper wrapper = federatedWrapper(validator);

    ServiceUnavailableException e =
        Assertions.assertThrows(
            ServiceUnavailableException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    Assertions.assertTrue(e.getMessage().contains(KMS_SOURCE), e.getMessage());
    Assertions.assertEquals(503, IcebergExceptionMapper.getErrorCode(e));
  }

  @Test
  void testFederatedPlaintextCreateNeverConsultsTheKms() {
    RecordingValidator validator = new RecordingValidator(KmsValidationStatus.INVALID);
    FederatedCatalogWrapper wrapper = federatedWrapper(validator);

    // This create fails on the unreachable remote, but only after confirmation would have run. An
    // untouched validator therefore shows plaintext skips the KMS on the federated path too.
    RESTException e =
        Assertions.assertThrows(
            RESTException.class,
            () -> wrapper.createTable(NAMESPACE, request(ImmutableMap.of()), false));

    Assertions.assertNotNull(e.getCause(), e::getMessage);
    Assertions.assertTrue(
        e.getCause().getMessage().contains(UNREACHABLE_REMOTE), e.getCause().getMessage());
    Assertions.assertTrue(validator.seen.isEmpty());
  }

  private CatalogWrapperForREST wrapper(
      @Nullable String kmsSource,
      @Nullable IcebergEncryptionPolicyEvaluator.KmsKeyValidator validator) {
    return withNamespace(
        new CatalogWrapperForREST("encryption-test", catalogConfig(kmsSource), validator));
  }

  /** Builds a federated wrapper whose remote is unreachable; see {@link #UNREACHABLE_REMOTE}. */
  private static FederatedCatalogWrapper federatedWrapper(
      @Nullable IcebergEncryptionPolicyEvaluator.KmsKeyValidator validator) {
    return new FederatedCatalogWrapper(
        "federated-encryption-test",
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConstants.CATALOG_BACKEND,
                "rest",
                IcebergConstants.URI,
                UNREACHABLE_REMOTE,
                IcebergConstants.ENCRYPTION_KMS_SOURCE,
                KMS_SOURCE)),
        validator);
  }

  private IcebergConfig catalogConfig(@Nullable String kmsSource) {
    ImmutableMap.Builder<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put(IcebergConstants.CATALOG_BACKEND, "memory")
            .put(IcebergConstants.WAREHOUSE, warehouse.toString());
    if (kmsSource != null) {
      properties.put(IcebergConstants.ENCRYPTION_KMS_SOURCE, kmsSource);
    }
    return new IcebergConfig(properties.build());
  }

  private static CatalogWrapperForREST withNamespace(CatalogWrapperForREST wrapper) {
    ((SupportsNamespaces) wrapper.getCatalog()).createNamespace(NAMESPACE);
    return wrapper;
  }

  /**
   * @param auxMode whether the service runs inside the Gravitino server, which is the only mode
   *     that can resolve a KMS validator.
   */
  private static IcebergCatalogWrapperManager manager(boolean auxMode) {
    Map<String, String> config = Maps.newHashMap();
    IcebergConfigProvider configProvider = IcebergConfigProviderFactory.create(config);
    configProvider.initialize(config);
    return new IcebergCatalogWrapperManager(
        config, configProvider, auxMode, configProvider.getMetalakeName());
  }

  /** Installs the catalog manager the aux-mode constructor subscribes to for cache invalidation. */
  private static void installCatalogManager() throws IllegalAccessException {
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "catalogManager", Mockito.mock(CatalogManager.class), true);
  }

  private static void installKmsClientRegistry(KmsClientRegistry registry)
      throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "kmsClientRegistry", registry, true);
  }

  /**
   * A registry that resolves no client for any reference. The validator built from it maps that to
   * {@code INVALID}, which is how an unknown provider or key reaches the caller as a 400.
   */
  private static KmsClientRegistry registryThatResolvesNoClient() {
    KmsClientRegistry registry = Mockito.mock(KmsClientRegistry.class);
    Mockito.when(registry.getClient(Mockito.any()))
        .thenThrow(new IllegalArgumentException("no KMS client for reference"));
    return registry;
  }

  /** Builds a create request for an encrypted table, which Iceberg allows only on v3 or later. */
  private static CreateTableRequest encryptedRequest(String keyId) {
    return request(
        ImmutableMap.of(
            IcebergConstants.ENCRYPTION_KEY_ID, keyId, TableProperties.FORMAT_VERSION, "3"));
  }

  private static CreateTableRequest request(Map<String, String> properties) {
    return CreateTableRequest.builder()
        .withName(TABLE.name())
        .withSchema(SCHEMA)
        .withPartitionSpec(PartitionSpec.unpartitioned())
        .setProperties(properties)
        .build();
  }
}
