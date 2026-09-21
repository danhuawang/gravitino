/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogPropertiesMetadata;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * Properties metadata for BigQuery catalog.
 *
 * <p>Defines BigQuery-specific catalog properties (project ID, proxy) and merges the inherited JDBC
 * properties from {@link JdbcCatalogPropertiesMetadata} so that {@code jdbc-url}, {@code
 * jdbc-driver}, {@code jdbc-user} and {@code jdbc-password} are declared and discoverable through
 * the API and the "Add catalog" UI. Without the merge these inherited entries would be dropped and
 * a create with only {@code project-id} would be accepted, then fail on first use.
 *
 * <p>{@code jdbc-url} and {@code jdbc-driver} are overridden as optional with BigQuery defaults:
 * the connector builds the full JDBC URL from friendly properties (see {@code
 * BigQueryCatalog#buildJdbcUrl}) and the Simba driver class name is well known. {@code jdbc-user}
 * (service account email) and {@code jdbc-password} (service account key file path) remain
 * required, so a create missing them is refused at create time rather than on first use.
 */
public class BigQueryCatalogPropertiesMetadata extends JdbcCatalogPropertiesMetadata {

  // BigQuery catalog property keys
  public static final String PROJECT_ID = "project-id";

  // Default BigQuery JDBC connection values.
  public static final String DEFAULT_JDBC_DRIVER = "com.simba.googlebigquery.jdbc42.Driver";
  public static final String DEFAULT_JDBC_URL =
      "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443";

  // Proxy configuration properties
  public static final String PROXY_HOST = "proxy-host";
  public static final String PROXY_PORT = "proxy-port";
  public static final String PROXY_USERNAME = "proxy-username";
  public static final String PROXY_PASSWORD = "proxy-password";

  private static final Map<String, PropertyEntry<?>> BIGQUERY_CATALOG_PROPERTY_ENTRIES =
      ImmutableMap.<String, PropertyEntry<?>>builder()
          // jdbc-url is built by the connector from friendly properties, so it is optional with a
          // sensible default rather than a hard requirement.
          .put(
              JdbcConfig.JDBC_URL.getKey(),
              PropertyEntry.stringOptionalPropertyEntry(
                  JdbcConfig.JDBC_URL.getKey(),
                  "BigQuery JDBC connection URL. Optional; built from project-id, jdbc-user and "
                      + "jdbc-password when not provided.",
                  false /* immutable */,
                  DEFAULT_JDBC_URL,
                  false /* hidden */))
          // The Simba BigQuery driver class name is well known, so default it instead of requiring
          // the administrator to know it.
          .put(
              JdbcConfig.JDBC_DRIVER.getKey(),
              PropertyEntry.stringOptionalPropertyEntry(
                  JdbcConfig.JDBC_DRIVER.getKey(),
                  "BigQuery JDBC driver class name.",
                  false /* immutable */,
                  DEFAULT_JDBC_DRIVER,
                  false /* hidden */))
          .put(
              PROJECT_ID,
              PropertyEntry.stringRequiredPropertyEntry(
                  PROJECT_ID, "Google Cloud Project ID", false /* immutable */, false /* hidden */))
          .put(
              PROXY_HOST,
              PropertyEntry.stringOptionalPropertyEntry(
                  PROXY_HOST,
                  "Proxy server hostname or IP address",
                  false /* immutable */,
                  null,
                  false /* hidden */))
          .put(
              PROXY_PORT,
              PropertyEntry.integerOptionalPropertyEntry(
                  PROXY_PORT,
                  "Proxy server port number",
                  false /* immutable */,
                  null,
                  false /* hidden */))
          .put(
              PROXY_USERNAME,
              PropertyEntry.stringOptionalPropertyEntry(
                  PROXY_USERNAME,
                  "Proxy authentication username",
                  false /* immutable */,
                  null,
                  false /* hidden */))
          .put(
              PROXY_PASSWORD,
              PropertyEntry.stringOptionalPropertyEntry(
                  PROXY_PASSWORD,
                  "Proxy authentication password",
                  false /* immutable */,
                  null,
                  true /* hidden */))
          .build();

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    // Merge the inherited JDBC properties (jdbc-url, jdbc-driver, jdbc-user, jdbc-password, pool
    // settings) with the BigQuery-specific ones. BigQuery's overrides for jdbc-url and jdbc-driver
    // take precedence over the parent's required declarations.
    Map<String, PropertyEntry<?>> merged = new HashMap<>(super.specificPropertyEntries());
    merged.putAll(BIGQUERY_CATALOG_PROPERTY_ENTRIES);
    return merged;
  }
}
