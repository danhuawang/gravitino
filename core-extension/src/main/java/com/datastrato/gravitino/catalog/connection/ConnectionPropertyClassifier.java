/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Classifies persisted Catalog properties that affect a provider's connection probe. */
public class ConnectionPropertyClassifier {
  private static final Set<String> COMMON_KEYS = Set.of("credential-providers", "ops-impl");
  private static final Set<String> JDBC_KEYS =
      Set.of(
          "jdbc-url",
          "jdbc-database",
          "jdbc-driver",
          "jdbc-user",
          "jdbc-password",
          "project-id",
          "proxy-host",
          "proxy-port",
          "proxy-username",
          "proxy-password");
  private static final Set<String> HIVE_KEYS =
      Set.of(
          "metastore.uris",
          "default.catalog",
          "client.pool-size",
          "client.pool-cache-eviction-interval-ms",
          "impersonation-enable");
  private static final Set<String> GLUE_KEYS =
      Set.of(
          "aws-region",
          "aws-glue-catalog-id",
          "aws-access-key-id",
          "aws-secret-access-key",
          "aws-glue-endpoint");
  private static final Set<String> KAFKA_KEYS = Set.of("bootstrap.servers");
  private static final Set<String> LAKEHOUSE_KEYS =
      Set.of(
          "catalog-backend",
          "catalog-backend-impl",
          "catalog-backend-name",
          "metastore",
          "uri",
          "warehouse",
          "default.catalog",
          "io-impl",
          "token",
          "token-provider",
          "token.provider");
  private static final Set<String> FILESET_KEYS =
      Set.of(
          "location",
          "filesystem-providers",
          "default-filesystem-provider",
          "filesystem-conn-timeout-secs",
          "disable-filesystem-ops",
          "config.resources");
  private static final Set<String> CLOUD_KEYS =
      Set.of(
          "client.region",
          "client.access-key-id",
          "client.access-key-secret",
          "gcs-service-account-file",
          "java.security.krb5.conf");

  /**
   * Returns whether a metadata change altered any property used by the connection probe.
   *
   * @param before The Catalog snapshot before the change.
   * @param after The Catalog snapshot after the change.
   * @return {@code true} when the persisted connection result must be invalidated.
   */
  public boolean connectionPropertiesChanged(
      CatalogConnectionSnapshot before, CatalogConnectionSnapshot after) {
    if (!Objects.equals(normalize(before.provider()), normalize(after.provider()))) {
      return true;
    }

    Set<String> keys = new HashSet<>(before.properties().keySet());
    keys.addAll(after.properties().keySet());
    for (String key : keys) {
      if (isConnectionProperty(after.provider(), key)
          && !Objects.equals(before.properties().get(key), after.properties().get(key))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether one property participates in a provider's connection probe.
   *
   * @param provider The Catalog provider.
   * @param property The persisted property name.
   * @return {@code true} when changing the property invalidates a Catalog test result.
   */
  public boolean isConnectionProperty(String provider, String property) {
    if (provider == null || property == null || "in-use".equals(property)) {
      return false;
    }
    String normalizedProvider = normalize(provider);
    String key = property.toLowerCase(Locale.ROOT);
    if (COMMON_KEYS.contains(key) || key.startsWith("gravitino.bypass.")) {
      return true;
    }

    if (normalizedProvider.startsWith("jdbc-")) {
      return JDBC_KEYS.contains(key) || key.startsWith("jdbc.pool.");
    }
    switch (normalizedProvider) {
      case "hive":
        return HIVE_KEYS.contains(key)
            || key.startsWith("kerberos.")
            || key.startsWith("authentication.");
      case "glue":
        return GLUE_KEYS.contains(key);
      case "kafka":
        return KAFKA_KEYS.contains(key);
      case "lakehouse-iceberg":
        return isLakehouseProperty(key)
            || key.equals("data-access")
            || key.equals("disable-rest-authz")
            || key.startsWith("gravitino-")
            || key.startsWith("rest-client-")
            || key.startsWith("rest.client.")
            || key.startsWith("authentication.")
            || key.startsWith("kerberos.")
            || key.startsWith("header.");
      case "lakehouse-paimon":
        return isLakehouseProperty(key)
            || key.startsWith("dlf.")
            || key.startsWith("dlf-")
            || key.startsWith("authentication.")
            || key.startsWith("kerberos.");
      case "lakehouse-hudi":
        return LAKEHOUSE_KEYS.contains(key)
            || key.startsWith("hive.metastore.")
            || key.startsWith("client.pool")
            || key.startsWith("authentication.")
            || key.startsWith("kerberos.");
      case "fileset":
      case "hadoop":
        return isFilesetProperty(key);
      default:
        return false;
    }
  }

  private boolean isLakehouseProperty(String key) {
    return LAKEHOUSE_KEYS.contains(key)
        || JDBC_KEYS.contains(key)
        || key.startsWith("jdbc-")
        || key.startsWith("jdbc.")
        || key.startsWith("s3-")
        || key.startsWith("s3.")
        || key.startsWith("oss-")
        || key.startsWith("oss.")
        || key.startsWith("fs.oss.")
        || key.startsWith("azure-")
        || key.startsWith("adls.")
        || key.startsWith("gcs-")
        || key.startsWith("cos-")
        || CLOUD_KEYS.contains(key);
  }

  private boolean isFilesetProperty(String key) {
    return FILESET_KEYS.contains(key)
        || key.startsWith("location-")
        || key.startsWith("fs.path.config.")
        || key.equals("fs.hdfs.impl.disable.cache")
        || key.startsWith("fs.s3a.")
        || key.startsWith("fs.gs.")
        || key.startsWith("fs.oss.")
        || key.startsWith("fs.cosn.")
        || key.startsWith("fs.azure.")
        || key.startsWith("ipc.client.")
        || key.startsWith("hadoop.security.authentication.")
        || key.startsWith("kerberos.")
        || key.startsWith("authentication.")
        || key.startsWith("s3-")
        || key.startsWith("oss-")
        || key.startsWith("azure-")
        || key.startsWith("gcs-")
        || key.startsWith("cos-")
        || CLOUD_KEYS.contains(key);
  }

  private String normalize(String provider) {
    return provider == null ? "" : provider.toLowerCase(Locale.ROOT);
  }
}
