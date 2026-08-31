/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestConnectionPropertyClassifier {
  private final ConnectionPropertyClassifier classifier = new ConnectionPropertyClassifier();

  @Test
  void testSupportedProviderConnectionKeys() {
    assertConnectionKey("jdbc-mysql", "jdbc-url");
    assertConnectionKey("jdbc-bigquery", "proxy-password");
    assertConnectionKey("hive", "metastore.uris");
    assertConnectionKey("hive", "kerberos.principal");
    assertConnectionKey("glue", "aws-glue-endpoint");
    assertConnectionKey("kafka", "bootstrap.servers");
    assertConnectionKey("lakehouse-iceberg", "rest-client-socket-timeout-ms");
    assertConnectionKey("lakehouse-iceberg", "rest.client.connection-timeout-ms");
    assertConnectionKey("lakehouse-iceberg", "disable-rest-authz");
    assertConnectionKey("lakehouse-iceberg", "jdbc-schema-version");
    assertConnectionKey("lakehouse-iceberg", "s3-secret-access-key");
    assertConnectionKey("lakehouse-paimon", "dlf.access-key-secret");
    assertConnectionKey("lakehouse-paimon", "fs.oss.endpoint");
    assertConnectionKey("lakehouse-paimon", "token-provider");
    assertConnectionKey("lakehouse-hudi", "hive.metastore.client.pool.size");
    assertConnectionKey("fileset", "location-east");
    assertConnectionKey("hadoop", "fs.path.config.archive");
    assertConnectionKey("fileset", "fs.s3a.connection.timeout");
    assertConnectionKey("hadoop", "fs.azure.account.auth.type.storage.dfs.core.windows.net");
    assertConnectionKey("hadoop", "ipc.client.connect.timeout");
    assertConnectionKey("fileset", "hadoop.security.authentication.kerberos.keytab");
    assertConnectionKey("kafka", "gravitino.bypass.security.protocol");
    assertConnectionKey("hive", "credential-providers");
    assertConnectionKey("fileset", "ops-impl");
  }

  @Test
  void testNonConnectionAndUnsupportedProviderKeys() {
    assertFalse(classifier.isConnectionProperty("jdbc-mysql", "in-use"));
    assertFalse(classifier.isConnectionProperty("lakehouse-iceberg", "table-format"));
    assertFalse(classifier.isConnectionProperty("lakehouse-iceberg", "catalog-cache-size"));
    assertFalse(classifier.isConnectionProperty("hive", "display-color"));
    assertFalse(classifier.isConnectionProperty("model", "uri"));
    assertFalse(classifier.isConnectionProperty("lakehouse-generic", "location"));
    assertFalse(classifier.isConnectionProperty("unknown", "jdbc-url"));
  }

  @Test
  void testChangeComparisonUsesPersistedValues() {
    CatalogConnectionSnapshot original =
        snapshot(
            "jdbc-mysql",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:mysql://host/db",
                "jdbc-password",
                "secret://metalake/catalog/password",
                "display-color",
                "red"));
    CatalogConnectionSnapshot sameConnection =
        snapshot(
            "jdbc-mysql",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:mysql://host/db",
                "jdbc-password",
                "secret://metalake/catalog/password",
                "display-color",
                "blue"));
    assertFalse(classifier.connectionPropertiesChanged(original, sameConnection));

    CatalogConnectionSnapshot changedSecret =
        snapshot(
            "jdbc-mysql",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:mysql://host/db",
                "jdbc-password",
                "secret://metalake/catalog/new-password"));
    assertTrue(classifier.connectionPropertiesChanged(original, changedSecret));

    CatalogConnectionSnapshot removedUrl =
        snapshot(
            "jdbc-mysql", ImmutableMap.of("jdbc-password", "secret://metalake/catalog/password"));
    assertTrue(classifier.connectionPropertiesChanged(original, removedUrl));

    CatalogConnectionSnapshot changedProvider = snapshot("hive", original.properties());
    assertTrue(classifier.connectionPropertiesChanged(original, changedProvider));
  }

  @Test
  void testConnectionTestSupportResolver() {
    ConnectionTestSupportResolver resolver = new ConnectionTestSupportResolver();
    assertTrue(resolver.supports("jdbc-postgresql"));
    assertTrue(resolver.supports("HIVE"));
    assertTrue(resolver.supports("glue"));
    assertTrue(resolver.supports("kafka"));
    assertTrue(resolver.supports("lakehouse-iceberg"));
    assertTrue(resolver.supports("lakehouse-paimon"));
    assertTrue(resolver.supports("lakehouse-hudi"));
    assertTrue(resolver.supports("fileset"));
    assertTrue(resolver.supports("hadoop"));
    assertFalse(resolver.supports("model"));
    assertFalse(resolver.supports("lakehouse-generic"));
    assertFalse(resolver.supports("unknown"));
    assertFalse(resolver.supports(null));
  }

  private void assertConnectionKey(String provider, String key) {
    assertTrue(classifier.isConnectionProperty(provider, key), provider + ":" + key);
  }

  private CatalogConnectionSnapshot snapshot(String provider, Map<String, String> properties) {
    return new CatalogConnectionSnapshot(10L, 1L, "catalog", provider, properties);
  }
}
