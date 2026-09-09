/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Fills in the {@code jdbc-driver} catalog property for JDBC-backed catalogs when the caller did
 * not supply it.
 *
 * <p>The driver class a catalog needs is determined by the catalog itself, not by the user: for a
 * pure JDBC catalog it follows from the provider (for example {@code jdbc-mysql} always uses the
 * MySQL driver), and for an Iceberg catalog with a JDBC metadata store it follows from the scheme
 * of the connection {@code uri}. Requiring the caller to also send {@code jdbc-driver} is therefore
 * redundant and was a frequent cause of catalog creation and connection-test failures.
 *
 * <p>This is the single place the enterprise REST layer uses to default the driver, so the create,
 * create-with-tags, and test-connection endpoints stay consistent. An explicitly supplied {@code
 * jdbc-driver} is always preserved.
 */
public final class JdbcDriverProperties {

  /** The Gravitino catalog property that carries the JDBC driver class name. */
  public static final String JDBC_DRIVER_PROPERTY = "jdbc-driver";

  private static final String ICEBERG_PROVIDER = "lakehouse-iceberg";
  private static final String CATALOG_BACKEND_PROPERTY = "catalog-backend";
  private static final String ICEBERG_JDBC_BACKEND = "jdbc";
  private static final String URI_PROPERTY = "uri";

  // Pure JDBC provider -> driver class. The provider fixes the driver, so it is a constant.
  private static final Map<String, String> PROVIDER_TO_DRIVER =
      ImmutableMap.<String, String>builder()
          .put("jdbc-bigquery", "com.simba.googlebigquery.jdbc42.Driver")
          .put("jdbc-clickhouse", "com.clickhouse.jdbc.ClickHouseDriver")
          .put("jdbc-doris", "com.mysql.cj.jdbc.Driver")
          .put("jdbc-hologres", "org.postgresql.Driver")
          .put("jdbc-maxcompute", "com.aliyun.odps.jdbc.OdpsDriver")
          .put("jdbc-mysql", "com.mysql.cj.jdbc.Driver")
          .put("jdbc-oceanbase", "com.mysql.cj.jdbc.Driver")
          .put("jdbc-oracle", "oracle.jdbc.OracleDriver")
          .put("jdbc-postgresql", "org.postgresql.Driver")
          .put("jdbc-sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver")
          .put("jdbc-starrocks", "com.mysql.cj.jdbc.Driver")
          .build();

  // JDBC URL scheme prefix -> driver class, for an Iceberg catalog whose metadata store is JDBC.
  // Unlike a pure JDBC catalog, the driver is not fixed by the provider because the same Iceberg
  // catalog can point at different databases; the scheme of "uri" determines it unambiguously.
  //
  // Only MySQL and PostgreSQL are auto-derived: they are the databases documented for the Iceberg
  // JDBC backend and their drivers ship on the server classpath (see the image's jdbc-drivers
  // directory). Iceberg's JDBC catalog imposes no database allowlist, so any other database remains
  // usable by supplying "jdbc-driver" explicitly.
  private static final Map<String, String> URI_SCHEME_TO_DRIVER =
      ImmutableMap.<String, String>builder()
          .put("jdbc:mysql:", "com.mysql.cj.jdbc.Driver")
          .put("jdbc:postgresql:", "org.postgresql.Driver")
          .build();

  private JdbcDriverProperties() {}

  /**
   * Returns a properties map with {@code jdbc-driver} filled in when it can be determined from the
   * provider or the connection {@code uri} and was not already supplied.
   *
   * <p>The input map is never modified. When no driver needs to be added, the original map is
   * returned unchanged; otherwise a copy with the added property is returned.
   *
   * @param provider the catalog provider (for example {@code jdbc-mysql} or {@code
   *     lakehouse-iceberg})
   * @param properties the catalog properties from the request, may be {@code null}
   * @return the properties, with a derived {@code jdbc-driver} added when applicable
   */
  public static Map<String, String> withDefaultDriver(
      String provider, Map<String, String> properties) {
    if (provider == null) {
      return properties;
    }

    Optional<String> driver = resolveDriver(provider, properties);
    if (!driver.isPresent()) {
      return properties;
    }

    Map<String, String> result =
        new HashMap<>(Optional.ofNullable(properties).orElse(ImmutableMap.of()));
    result.putIfAbsent(JDBC_DRIVER_PROPERTY, driver.get());
    return result;
  }

  @VisibleForTesting
  static Optional<String> resolveDriver(String provider, Map<String, String> properties) {
    // An explicit driver is authoritative and never overridden.
    if (properties != null && StringUtils.isNotBlank(properties.get(JDBC_DRIVER_PROPERTY))) {
      return Optional.empty();
    }

    String normalizedProvider = provider.toLowerCase(Locale.ROOT);

    String jdbcDriver = PROVIDER_TO_DRIVER.get(normalizedProvider);
    if (jdbcDriver != null) {
      return Optional.of(jdbcDriver);
    }

    if (ICEBERG_PROVIDER.equals(normalizedProvider) && properties != null) {
      boolean jdbcBackend =
          ICEBERG_JDBC_BACKEND.equalsIgnoreCase(properties.get(CATALOG_BACKEND_PROPERTY));
      if (jdbcBackend) {
        return driverForUriScheme(properties.get(URI_PROPERTY));
      }
    }

    return Optional.empty();
  }

  private static Optional<String> driverForUriScheme(String uri) {
    if (StringUtils.isBlank(uri)) {
      return Optional.empty();
    }
    String lowerUri = uri.trim().toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> entry : URI_SCHEME_TO_DRIVER.entrySet()) {
      if (lowerUri.startsWith(entry.getKey())) {
        return Optional.of(entry.getValue());
      }
    }
    return Optional.empty();
  }
}
