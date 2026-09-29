/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryExceptionConverter;
import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryTypeConverter;
import com.datastrato.gravitino.catalog.bigquery.operation.BigQueryDatabaseOperations;
import com.datastrato.gravitino.catalog.bigquery.operation.BigQueryTableOperations;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcCatalog;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.capability.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of a BigQuery catalog in Apache Gravitino. */
public class BigQueryCatalog extends JdbcCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(BigQueryCatalog.class);

  public static final BigQueryTablePropertiesMetadata BIGQUERY_TABLE_PROPERTIES_META =
      new BigQueryTablePropertiesMetadata();

  private static final String STORAGE_API_OPTIONS =
      "EnableHighThroughPutAPI=0;ForceHTAPI=0;EnableWriteAPI=0;";

  private static final Pattern STORAGE_API_OPTION_PATTERN =
      Pattern.compile("(?i)(?:EnableHighThroughPutAPI|ForceHTAPI|EnableWriteAPI)\\s*=.*");

  private BigQueryClientPool clientPool;

  @Override
  public BigQueryCatalog withCatalogConf(Map<String, String> conf) {
    // Fail fast on missing required configuration so a bad catalog is rejected at create time
    // rather than on first use.
    validateConfiguration(conf);

    // Apply BigQuery defaults for optional JDBC properties. Property-metadata defaults are only
    // used when reading through the metadata; the raw conf handed to the JDBC framework does not
    // get them, so the driver would otherwise be blank at initialize() time.
    Map<String, String> withDefaults = applyJdbcDefaults(conf);

    // Build JDBC URL from friendly properties before setting config
    Map<String, String> processedConfig = new HashMap<>(buildJdbcUrl(withDefaults));
    // This catalog performs metadata and DDL operations, which do not use Storage APIs.
    // Simba eagerly creates gRPC clients when these options are enabled. Prevent their creation
    // instead of relying on driver cleanup, including connections that fail during initialization.
    processedConfig.put(
        JdbcConfig.JDBC_URL.getKey(),
        disableStorageApis(processedConfig.get(JdbcConfig.JDBC_URL.getKey())));
    String connectionPropertiesKey = CATALOG_BYPASS_PREFIX + "connectionProperties";
    if (processedConfig.containsKey(connectionPropertiesKey)) {
      processedConfig.put(
          connectionPropertiesKey,
          disableStorageApis(processedConfig.get(connectionPropertiesKey)));
    }

    // Initialize BigQuery API client pool for cross-region dataset listing
    this.clientPool = new BigQueryClientPool(processedConfig);

    // Call parent with processed config
    return (BigQueryCatalog) super.withCatalogConf(processedConfig);
  }

  /**
   * Applies BigQuery defaults for optional JDBC properties that the JDBC framework requires to be
   * present at initialization. {@code jdbc-driver} defaults to the Simba driver class; {@code
   * jdbc-url} is left to {@link #buildJdbcUrl} which constructs it from the other properties.
   *
   * @param config the catalog configuration
   * @return a new configuration map with defaults applied
   */
  private Map<String, String> applyJdbcDefaults(Map<String, String> config) {
    Map<String, String> withDefaults = new HashMap<>(config);
    withDefaults.putIfAbsent(
        JdbcConfig.JDBC_DRIVER.getKey(), BigQueryCatalogPropertiesMetadata.DEFAULT_JDBC_DRIVER);
    return withDefaults;
  }

  /**
   * Validates the required catalog configuration. This performs no external connections; it only
   * checks that the mandatory inputs are present so an invalid catalog is refused at create time
   * with a clear message instead of failing later on first use.
   *
   * @param config the catalog configuration
   * @throws IllegalArgumentException if a required property is missing
   */
  private void validateConfiguration(Map<String, String> config) {
    if (StringUtils.isBlank(config.get(BigQueryCatalogPropertiesMetadata.PROJECT_ID))) {
      throw new IllegalArgumentException("project-id is required for BigQuery catalog");
    }
    // When jdbc-url already carries the project and authentication parameters, the connector uses
    // it as-is (see buildJdbcUrl) and the service account email is embedded in the URL. Requiring a
    // separate jdbc-user/jdbc-password in that case would reject existing catalogs on reload, so
    // only enforce the friendly-property credentials when the URL is not pre-authenticated.
    if (!hasPreAuthenticatedJdbcUrl(config)) {
      if (StringUtils.isBlank(config.get(JdbcConfig.USERNAME.getKey()))) {
        throw new IllegalArgumentException(
            "jdbc-user (service account email) is required for BigQuery catalog");
      }
      if (StringUtils.isBlank(config.get(JdbcConfig.PASSWORD.getKey()))) {
        throw new IllegalArgumentException(
            "jdbc-password (service account key file path) is required for BigQuery catalog");
      }
    }
    validateProxyConfiguration(config);
  }

  /**
   * Returns whether {@code jdbc-url} already contains the project and authentication parameters, in
   * which case the connector uses the URL as-is instead of building it from the friendly
   * properties. This mirrors the detection in {@link #buildJdbcUrl}.
   *
   * @param config the catalog configuration
   * @return true if the JDBC URL is already self-authenticated
   */
  private boolean hasPreAuthenticatedJdbcUrl(Map<String, String> config) {
    String jdbcUrl = config.get(JdbcConfig.JDBC_URL.getKey());
    return StringUtils.isNotBlank(jdbcUrl)
        && jdbcUrl.contains("ProjectId=")
        && (jdbcUrl.contains("OAuthServiceAcctEmail=") || jdbcUrl.contains("OAuthType="));
  }

  /**
   * Validates the proxy configuration when a proxy host is supplied.
   *
   * @param config the catalog configuration
   * @throws IllegalArgumentException if the proxy configuration is invalid
   */
  private void validateProxyConfiguration(Map<String, String> config) {
    String proxyHost = config.get(BigQueryCatalogPropertiesMetadata.PROXY_HOST);
    String proxyPort = config.get(BigQueryCatalogPropertiesMetadata.PROXY_PORT);
    if (StringUtils.isBlank(proxyHost)) {
      return;
    }
    if (StringUtils.isBlank(proxyPort)) {
      throw new IllegalArgumentException("proxy-port is required when proxy-host is specified");
    }
    try {
      int port = Integer.parseInt(proxyPort);
      if (port <= 0 || port > 65535) {
        throw new IllegalArgumentException("proxy-port must be between 1 and 65535");
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("proxy-port must be a valid integer", e);
    }
  }

  /**
   * Gets the BigQuery API client pool.
   *
   * <p>This method provides access to the BigQuery API client pool for operations that need to
   * interact with BigQuery APIs directly, such as cross-region dataset operations or advanced
   * metadata retrieval that cannot be performed through JDBC.
   *
   * @return BigQuery client pool, may be null if not initialized
   */
  public BigQueryClientPool getClientPool() {
    return clientPool;
  }

  @Override
  public void close() throws IOException {
    if (clientPool != null) {
      try {
        clientPool.close();
      } catch (Exception e) {
        LOG.warn("Error closing BigQuery client pool", e);
      }
    }
    super.close();
  }

  @Override
  public String shortName() {
    return "jdbc-bigquery";
  }

  /**
   * Build JDBC URL from individual BigQuery configuration components. If jdbc-url contains
   * authentication parameters, use it directly. Otherwise, enhance the base URL with project-id,
   * jdbc-user (service account email), and jdbc-password (key file path), and proxy configuration.
   */
  private Map<String, String> buildJdbcUrl(Map<String, String> config) {

    String jdbcUrl = config.get(JdbcConfig.JDBC_URL.getKey());

    // If jdbc-url already contains authentication parameters, use it as-is
    if (hasPreAuthenticatedJdbcUrl(config)) {
      LOG.info("Using existing JDBC URL with authentication parameters");
      return config;
    }

    // Extract BigQuery-specific properties
    String projectId = config.get(BigQueryCatalogPropertiesMetadata.PROJECT_ID);
    String keyFilePath = config.get(JdbcConfig.PASSWORD.getKey()); // Key file path
    String serviceAccountEmail = config.get(JdbcConfig.USERNAME.getKey()); // Service account email

    // Extract proxy configuration
    String proxyHost = config.get(BigQueryCatalogPropertiesMetadata.PROXY_HOST);
    String proxyPort = config.get(BigQueryCatalogPropertiesMetadata.PROXY_PORT);
    String proxyUsername = config.get(BigQueryCatalogPropertiesMetadata.PROXY_USERNAME);
    String proxyPassword = config.get(BigQueryCatalogPropertiesMetadata.PROXY_PASSWORD);

    // Validate required properties
    if (StringUtils.isBlank(projectId)) {
      throw new IllegalArgumentException("project-id is required for BigQuery catalog");
    }
    if (StringUtils.isBlank(keyFilePath)) {
      throw new IllegalArgumentException(
          "jdbc-password (key file path) is required for BigQuery catalog");
    }

    // Validate proxy configuration if provided
    if (StringUtils.isNotBlank(proxyHost)) {
      if (StringUtils.isBlank(proxyPort)) {
        throw new IllegalArgumentException("proxy-port is required when proxy-host is specified");
      }
      try {
        int port = Integer.parseInt(proxyPort);
        if (port <= 0 || port > 65535) {
          throw new IllegalArgumentException("proxy-port must be between 1 and 65535");
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("proxy-port must be a valid integer", e);
      }
    }

    // Build complete JDBC URL with authentication parameters
    // Use OAuthType=0 for service account authentication
    StringBuilder urlBuilder = new StringBuilder();

    // Start with base URL or construct default URL
    if (StringUtils.isNotBlank(jdbcUrl)) {
      // Ensure URL ends with semicolon for parameter separation
      String baseUrl = jdbcUrl.endsWith(";") ? jdbcUrl : jdbcUrl + ";";
      urlBuilder.append(baseUrl);
    } else {
      // Use default BigQuery JDBC URL
      urlBuilder.append("jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;");
    }

    urlBuilder.append("ProjectId=").append(projectId).append(";");
    urlBuilder.append("OAuthType=0;");

    // Add service account email if provided
    if (StringUtils.isNotBlank(serviceAccountEmail)) {
      urlBuilder.append("OAuthServiceAcctEmail=").append(serviceAccountEmail).append(";");
    }

    urlBuilder.append("OAuthPvtKeyPath=").append(keyFilePath).append(";");

    // Add proxy configuration if provided
    if (StringUtils.isNotBlank(proxyHost)) {
      urlBuilder.append("ProxyHost=").append(proxyHost).append(";");
      urlBuilder.append("ProxyPort=").append(proxyPort).append(";");

      // Add proxy authentication if provided
      if (StringUtils.isNotBlank(proxyUsername)) {
        urlBuilder.append("ProxyUid=").append(proxyUsername).append(";");

        if (StringUtils.isNotBlank(proxyPassword)) {
          urlBuilder.append("ProxyPwd=").append(proxyPassword).append(";");
        }
      }

      LOG.info("Added proxy configuration: {}:{}", proxyHost, proxyPort);
    }

    String completeJdbcUrl = urlBuilder.toString();

    // Create new config map with complete jdbc-url
    HashMap<String, String> newConfig = new HashMap<>(config);
    newConfig.put(JdbcConfig.JDBC_URL.getKey(), completeJdbcUrl);

    // Log the constructed URL (without sensitive info)
    LOG.info("Built BigQuery JDBC URL for project: {}", projectId);
    LOG.debug(
        "Complete JDBC URL: {}",
        completeJdbcUrl
            .replaceAll("OAuthPvtKeyPath=[^;]+", "OAuthPvtKeyPath=[REDACTED]")
            .replaceAll("ProxyPwd=[^;]+", "ProxyPwd=[REDACTED]"));

    return newConfig;
  }

  @Override
  public Capability newCapability() {
    return new BigQueryCatalogCapability();
  }

  @Override
  public PropertiesMetadata catalogPropertiesMetadata() throws UnsupportedOperationException {
    return new BigQueryCatalogPropertiesMetadata();
  }

  @Override
  public PropertiesMetadata schemaPropertiesMetadata() throws UnsupportedOperationException {
    return new BigQuerySchemaPropertiesMetadata();
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return BIGQUERY_TABLE_PROPERTIES_META;
  }

  @Override
  protected JdbcExceptionConverter createExceptionConverter() {
    return new BigQueryExceptionConverter();
  }

  @Override
  protected JdbcTypeConverter createJdbcTypeConverter() {
    return new BigQueryTypeConverter();
  }

  @Override
  protected JdbcDatabaseOperations createJdbcDatabaseOperations() {
    BigQueryDatabaseOperations operations = new BigQueryDatabaseOperations();
    operations.setClientPool(this.clientPool);
    return operations;
  }

  @Override
  protected JdbcTableOperations createJdbcTableOperations() {
    BigQueryTableOperations operations = new BigQueryTableOperations();
    operations.setClientPool(this.clientPool);
    return operations;
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new BigQueryColumnDefaultValueConverter();
  }

  private static String disableStorageApis(String properties) {
    StringBuilder result = new StringBuilder();
    for (String property : properties.split(";")) {
      String trimmed = property.trim();
      if (!trimmed.isEmpty() && !STORAGE_API_OPTION_PATTERN.matcher(trimmed).matches()) {
        // Preserve parameter values verbatim; trailing spaces can be part of a password.
        result.append(property).append(';');
      }
    }
    return result.append(STORAGE_API_OPTIONS).toString();
  }
}
