/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryExceptionConverter;
import com.datastrato.gravitino.catalog.bigquery.converter.BigQueryTypeConverter;
import com.datastrato.gravitino.catalog.bigquery.operation.BigQueryDatabaseOperations;
import com.datastrato.gravitino.catalog.bigquery.operation.BigQueryTableOperations;
import java.util.HashMap;
import java.util.Map;
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

  private BigQueryClientPool clientPool;

  @Override
  public BigQueryCatalog withCatalogConf(Map<String, String> conf) {
    // Build JDBC URL from friendly properties before setting config
    Map<String, String> processedConfig = buildJdbcUrl(conf);

    // Initialize BigQuery API client pool for cross-region dataset listing
    this.clientPool = new BigQueryClientPool(processedConfig);

    // Call parent with processed config
    return (BigQueryCatalog) super.withCatalogConf(processedConfig);
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
  public void close() throws java.io.IOException {
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
    if (StringUtils.isNotBlank(jdbcUrl)
        && jdbcUrl.contains("ProjectId=")
        && (jdbcUrl.contains("OAuthServiceAcctEmail=") || jdbcUrl.contains("OAuthType="))) {
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
}
