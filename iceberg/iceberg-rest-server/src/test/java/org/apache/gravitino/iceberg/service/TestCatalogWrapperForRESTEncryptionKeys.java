/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.iceberg.service;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.credential.CredentialPrivilege;
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
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Pins that the Iceberg REST catalog wrapper carries encryption key properties straight through.
 *
 * <p>The key a request names is validated once, by the admission service above this wrapper, so the
 * wrapper itself never consults a KMS and never rejects a create over a key. What it must still
 * guarantee is that the key identity survives the round trip untouched, on the local backend and on
 * the federated one, whether or not the catalog names an {@code encryption-kms-source}, and that
 * building a catalog through {@link IcebergCatalogWrapperManager} reaches for no server-owned
 * component to do it.
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

  /**
   * Clears the server components installed by the manager test so a test that expects an
   * uninitialized {@link GravitinoEnv} cannot pass on state left behind by an earlier one.
   */
  @AfterEach
  void clearServerComponents() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogManager", null, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "kmsClientRegistry", null, true);
  }

  @Test
  void testPlaintextCreateOnAKmsBoundCatalogIsUnaffected() {
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE);

    wrapper.createTable(NAMESPACE, request(ImmutableMap.of()), false);

    Assertions.assertTrue(wrapper.getCatalog().tableExists(TABLE));
  }

  @Test
  void testEncryptedCreateOnAKmsBoundCatalogKeepsTheRequestedKey() {
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE);

    LoadTableResponse response = wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    Assertions.assertEquals(
        "k-1", response.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testUnboundCatalogPassesNativeIcebergEncryptionThrough() {
    // A catalog that opted into no Gravitino KMS may still drive Iceberg's own encryption, so the
    // request must be neither confirmed nor rejected here.
    CatalogWrapperForREST wrapper = wrapper(null);

    LoadTableResponse response = wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    Assertions.assertEquals(
        "k-1", response.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testEncryptionKeyIdentitySurvivesCreateToLoad() {
    CatalogWrapperForREST wrapper = wrapper(KMS_SOURCE);
    wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    LoadTableResponse loaded = wrapper.loadTable(TABLE, false, CredentialPrivilege.READ);

    Assertions.assertEquals(
        "k-1", loaded.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testManagerKeepsKmsBoundCatalogsOffServerComponents() throws IllegalAccessException {
    installCatalogManager();

    // The KMS registry is deliberately left uninitialized. GravitinoEnv throws when it is read, so
    // this create succeeds only if building a KMS-bound catalog reaches for no server-owned
    // component at all, which is what the removal of the wrapper's own confirmation leaves behind.
    CatalogWrapperForREST wrapper =
        withNamespace(manager(true).createCatalogWrapper("bound", catalogConfig(KMS_SOURCE)));

    LoadTableResponse response = wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false);

    Assertions.assertEquals(
        "k-1", response.tableMetadata().properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  @Test
  void testFederatedEncryptedCreateIsForwardedRatherThanRejectedLocally() {
    FederatedCatalogWrapper wrapper = federatedWrapper();

    // Failing on the unreachable remote, rather than with a local rejection, is what shows the
    // federated create path no longer decides anything about the key it carries.
    RESTException e =
        Assertions.assertThrows(
            RESTException.class,
            () -> wrapper.createTable(NAMESPACE, encryptedRequest("k-1"), false));

    Assertions.assertNotNull(e.getCause(), e::getMessage);
    Assertions.assertTrue(
        e.getCause().getMessage().contains(UNREACHABLE_REMOTE), e.getCause().getMessage());
  }

  private CatalogWrapperForREST wrapper(@Nullable String kmsSource) {
    return withNamespace(new CatalogWrapperForREST("encryption-test", catalogConfig(kmsSource)));
  }

  /** Builds a federated wrapper whose remote is unreachable; see {@link #UNREACHABLE_REMOTE}. */
  private static FederatedCatalogWrapper federatedWrapper() {
    return new FederatedCatalogWrapper(
        "federated-encryption-test",
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConstants.CATALOG_BACKEND,
                "rest",
                IcebergConstants.URI,
                UNREACHABLE_REMOTE,
                IcebergConstants.ENCRYPTION_KMS_SOURCE,
                KMS_SOURCE)));
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
   * @param auxMode whether the service runs inside the Gravitino server.
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
