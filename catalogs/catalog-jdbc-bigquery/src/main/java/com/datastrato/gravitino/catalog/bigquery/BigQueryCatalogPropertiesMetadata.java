/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogPropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * Properties metadata for BigQuery catalog.
 *
 * <p>Defines BigQuery-specific catalog properties including project ID and authentication
 * credentials.
 */
public class BigQueryCatalogPropertiesMetadata extends JdbcCatalogPropertiesMetadata {

  // BigQuery catalog property keys
  public static final String PROJECT_ID = "project-id";
  // Proxy configuration properties
  public static final String PROXY_HOST = "proxy-host";
  public static final String PROXY_PORT = "proxy-port";
  public static final String PROXY_USERNAME = "proxy-username";
  public static final String PROXY_PASSWORD = "proxy-password";

  private static final Map<String, PropertyEntry<?>> BIGQUERY_CATALOG_PROPERTY_ENTRIES =
      ImmutableMap.<String, PropertyEntry<?>>builder()
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
    return BIGQUERY_CATALOG_PROPERTY_ENTRIES;
  }
}
