/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest.converter;

import com.datastrato.gravitino.dto.ConnectionDTO;
import com.datastrato.gravitino.dto.ConnectionOverviewDTO;
import com.datastrato.gravitino.dto.ConnectionTestStatusDTO;
import com.datastrato.gravitino.dto.CredentialProviderStatusDTO;
import com.google.common.base.Preconditions;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.catalog.glue.GlueConstants;
import org.apache.gravitino.catalog.hive.HiveConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.paimon.PaimonConstants;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.storage.AzureProperties;
import org.apache.gravitino.storage.GCSProperties;
import org.apache.gravitino.storage.OSSProperties;
import org.apache.gravitino.storage.S3Properties;

/** Utility class for converting Catalogs to ConnectionDTOs and computing connection metrics. */
public class ConnectionConverter {

  private static final String DEFAULT_VALUE = "--";
  private static final String JDBC_PREFIX = "jdbc:";
  private static final String BIGQUERY_JDBC_PREFIX = "jdbc:bigquery://";
  private static final String ORACLE_THIN_JDBC_PREFIX = "jdbc:oracle:thin:";
  private static final Pattern ORACLE_TNS_DESCRIPTION_PATTERN =
      Pattern.compile("^\\(\\s*DESCRIPTION\\s*=", Pattern.CASE_INSENSITIVE);
  private static final Pattern ORACLE_TNS_ADDRESS_PATTERN =
      Pattern.compile("\\(\\s*ADDRESS\\s*=", Pattern.CASE_INSENSITIVE);
  private static final Pattern ORACLE_TNS_PROTOCOL_PATTERN =
      Pattern.compile("\\(\\s*PROTOCOL\\s*=\\s*([^()]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ORACLE_TNS_HOST_PATTERN =
      Pattern.compile("\\(\\s*HOST\\s*=\\s*([^()]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ORACLE_TNS_PORT_PATTERN =
      Pattern.compile("\\(\\s*PORT\\s*=\\s*([^()]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ORACLE_TNS_SERVICE_NAME_PATTERN =
      Pattern.compile("\\(\\s*SERVICE_NAME\\s*=\\s*([^()]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ORACLE_TNS_SID_PATTERN =
      Pattern.compile("\\(\\s*SID\\s*=\\s*([^()]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SAFE_RAW_AUTHORITY_PATTERN =
      Pattern.compile("^([A-Za-z0-9._-]+)(?::[0-9]+)?$");

  private ConnectionConverter() {}

  /**
   * Converts a Catalog to a whitelisted Connect overview DTO without exposing its property map.
   *
   * @param catalog The catalog entity or DTO.
   * @param testStatus The latest valid manual connection test status.
   * @return The converted Connection overview DTO.
   */
  public static ConnectionOverviewDTO toConnectionOverviewDTO(
      Catalog catalog, ConnectionTestStatusDTO testStatus) {
    return toConnectionOverviewDTO(catalog, testStatus, new CredentialProviderStatusDTO[0]);
  }

  /**
   * Converts a Catalog to a whitelisted Connect overview DTO with credential provider statuses.
   *
   * @param catalog The catalog entity or DTO.
   * @param testStatus The latest valid manual Catalog connection test status.
   * @param credentialProviders The configured credential providers and their latest test statuses.
   * @return The converted Connection overview DTO.
   */
  public static ConnectionOverviewDTO toConnectionOverviewDTO(
      Catalog catalog,
      ConnectionTestStatusDTO testStatus,
      CredentialProviderStatusDTO[] credentialProviders) {
    Preconditions.checkArgument(catalog != null, "catalog cannot be null");
    Preconditions.checkArgument(testStatus != null, "testStatus cannot be null");
    Preconditions.checkArgument(credentialProviders != null, "credentialProviders cannot be null");

    Map<String, String> properties = catalog.properties();
    String endpoint = resolveEndpoint(catalog.provider(), properties);
    return new ConnectionOverviewDTO(
        catalog.name(),
        catalog.type(),
        catalog.provider(),
        catalog.comment(),
        getProperty(properties, Catalog.CLOUD_NAME),
        getProperty(properties, Catalog.CLOUD_REGION_CODE),
        DTOConverters.toDTO(catalog.auditInfo()),
        endpoint,
        testStatus,
        credentialProviders);
  }

  /**
   * Converts a Catalog and its schema count to a ConnectionDTO.
   *
   * @param catalog The catalog entity or DTO.
   * @param schemaCount The direct schema count for this catalog, or {@code null} if unavailable.
   * @return The converted ConnectionDTO.
   */
  public static ConnectionDTO toConnectionDTO(Catalog catalog, @Nullable Long schemaCount) {
    return toConnectionDTO(catalog, schemaCount, null);
  }

  /**
   * Converts a Catalog and its schema count to a ConnectionDTO using an Enterprise-resolved
   * credential provider list when the public catalog properties do not contain one.
   *
   * @param catalog The catalog entity or DTO.
   * @param schemaCount The direct schema count for this catalog, or {@code null} if unavailable.
   * @param credentialProviders The canonical credential provider list, or {@code null} if
   *     unavailable.
   * @return The converted ConnectionDTO.
   */
  public static ConnectionDTO toConnectionDTO(
      Catalog catalog, @Nullable Long schemaCount, @Nullable String credentialProviders) {
    if (catalog == null) {
      return null;
    }

    String name = catalog.name();
    Map<String, String> properties = catalog.properties();
    String type = resolveDisplayType(catalog.provider(), properties);
    String endpoint = resolveEndpoint(catalog.provider(), properties);
    String credential = resolveCredential(properties, credentialProviders);

    return new ConnectionDTO(name, type, endpoint, credential, schemaCount);
  }

  /**
   * Converts an array of catalogs and schema counts map to an array of ConnectionDTOs.
   *
   * @param catalogs The array of catalogs.
   * @param schemaCounts Map of catalog name to schema count. A missing entry indicates that the
   *     count is unavailable.
   * @return The array of ConnectionDTOs.
   */
  public static ConnectionDTO[] toConnectionDTOs(
      Catalog[] catalogs, Map<String, Long> schemaCounts) {
    return toConnectionDTOs(catalogs, schemaCounts, null);
  }

  /**
   * Converts catalogs to ConnectionDTOs using schema counts and Enterprise-resolved credential
   * provider lists keyed by catalog name.
   *
   * @param catalogs The array of catalogs.
   * @param schemaCounts Map of catalog name to schema count. A missing entry indicates that the
   *     count is unavailable.
   * @param credentialProviders Map of catalog name to canonical credential provider list, or {@code
   *     null} if unavailable.
   * @return The array of ConnectionDTOs.
   */
  public static ConnectionDTO[] toConnectionDTOs(
      Catalog[] catalogs,
      Map<String, Long> schemaCounts,
      @Nullable Map<String, String> credentialProviders) {
    if (catalogs == null) {
      return new ConnectionDTO[0];
    }

    return Arrays.stream(catalogs)
        .filter(Objects::nonNull)
        .map(
            catalog -> {
              Long count = schemaCounts != null ? schemaCounts.get(catalog.name()) : null;
              String providers =
                  credentialProviders != null ? credentialProviders.get(catalog.name()) : null;
              return toConnectionDTO(catalog, count, providers);
            })
        .toArray(ConnectionDTO[]::new);
  }

  /**
   * Calculates the distinct system count across an array of ConnectionDTOs.
   *
   * @param connections The array of connections.
   * @return The count of distinct underlying systems.
   */
  public static int calculateSystemCount(ConnectionDTO[] connections) {
    if (connections == null || connections.length == 0) {
      return 0;
    }

    Set<String> distinctEndpoints = new HashSet<>();
    int unknownEndpointCount = 0;

    for (ConnectionDTO connection : connections) {
      if (connection == null) {
        continue;
      }
      String endpoint = connection.getEndpoint();
      if (StringUtils.isNotBlank(endpoint) && !DEFAULT_VALUE.equals(endpoint.trim())) {
        distinctEndpoints.add(normalizeEndpoint(endpoint));
      } else {
        unknownEndpointCount++;
      }
    }

    return distinctEndpoints.size() + unknownEndpointCount;
  }

  /**
   * Resolves the user-friendly display type for a catalog.
   *
   * @param provider The catalog provider.
   * @param properties The catalog properties.
   * @return The user-friendly display type string.
   */
  public static String resolveDisplayType(String provider, Map<String, String> properties) {
    if (StringUtils.isBlank(provider)) {
      return DEFAULT_VALUE;
    }

    String lowerProvider = provider.trim().toLowerCase(Locale.ROOT);

    if ("lakehouse-iceberg".equals(lowerProvider)) {
      String backend =
          getProperty(properties, IcebergConstants.CATALOG_BACKEND, "iceberg.catalog-backend");
      if (StringUtils.isBlank(backend)) {
        return "Iceberg REST";
      }
      String lowerBackend = backend.trim().toLowerCase(Locale.ROOT);
      switch (lowerBackend) {
        case "rest":
          return "Iceberg REST";
        case "hive":
          return "Iceberg Hive";
        case "jdbc":
          return "Iceberg JDBC";
        case "glue":
          return "Iceberg Glue";
        default:
          return "Iceberg " + toTitleCase(backend);
      }
    }

    switch (lowerProvider) {
      case "hive":
        return "Hive";
      case "jdbc-mysql":
        return "MySQL";
      case "jdbc-postgresql":
        return "PostgreSQL";
      case "lakehouse-paimon":
        return "Paimon";
      case "fileset":
      case "hadoop":
        return "Fileset";
      case "kafka":
        return "Kafka";
      case "jdbc-doris":
        return "Doris";
      case "jdbc-starrocks":
        return "StarRocks";
      case "jdbc-sqlserver":
        return "SQL Server";
      case "jdbc-oceanbase":
        return "OceanBase";
      case "jdbc-oracle":
        return "Oracle";
      case "jdbc-bigquery":
        return "BigQuery";
      default:
        return toTitleCase(provider);
    }
  }

  /**
   * Resolves a sanitized display endpoint from the catalog provider and properties.
   *
   * @param provider The catalog provider.
   * @param properties The catalog properties.
   * @return The sanitized endpoint string, or "--" if it is absent or cannot be exposed safely.
   */
  public static String resolveEndpoint(String provider, Map<String, String> properties) {
    if (StringUtils.isBlank(provider) || properties == null || properties.isEmpty()) {
      return DEFAULT_VALUE;
    }

    String lowerProvider = provider.trim().toLowerCase(Locale.ROOT);
    if (lowerProvider.startsWith("jdbc-")) {
      return resolveJdbcEndpoint(lowerProvider, properties);
    }

    if (lowerProvider.contains("iceberg")) {
      String backend =
          getProperty(properties, IcebergConstants.CATALOG_BACKEND, "iceberg.catalog-backend");
      if (StringUtils.equalsIgnoreCase(backend, "glue")) {
        return resolveGlueEndpoint(properties);
      }
      if (StringUtils.equalsIgnoreCase(backend, "jdbc")) {
        return resolveJdbcEndpoint(
            lowerProvider,
            getProperty(properties, IcebergConstants.URI),
            getProperty(properties, IcebergConstants.GRAVITINO_JDBC_DRIVER));
      }
      return sanitizeUriListEndpoint(getProperty(properties, IcebergConstants.URI));
    }

    switch (lowerProvider) {
      case "hive":
        return sanitizeUriListEndpoint(getProperty(properties, HiveConstants.METASTORE_URIS));
      case "glue":
        return resolveGlueEndpoint(properties);
      case "kafka":
        return sanitizeAuthorityListEndpoint(getProperty(properties, "bootstrap.servers"));
      case "fileset":
      case "hadoop":
      case "lakehouse-generic":
        return sanitizeFilesystemLocation(getProperty(properties, Catalog.PROPERTY_LOCATION));
      case "lakehouse-hudi":
      case "hudi":
        return sanitizeUriListEndpoint(getProperty(properties, "uri"));
      case "lakehouse-paimon":
      case "paimon":
        String backend = getProperty(properties, PaimonConstants.CATALOG_BACKEND);
        if (StringUtils.equalsIgnoreCase(backend, "filesystem")) {
          return sanitizeFilesystemLocation(getProperty(properties, PaimonConstants.WAREHOUSE));
        }
        if (StringUtils.equalsIgnoreCase(backend, "jdbc")) {
          return resolveJdbcEndpoint(
              lowerProvider,
              getProperty(properties, PaimonConstants.URI),
              getProperty(properties, PaimonConstants.GRAVITINO_JDBC_DRIVER));
        }
        return sanitizeUriListEndpoint(getProperty(properties, PaimonConstants.URI));
      default:
        return DEFAULT_VALUE;
    }
  }

  /**
   * Resolves the credential type string from catalog properties based on Gravitino credential
   * vending taxonomy.
   *
   * @param properties The catalog properties.
   * @return The credential type string, or "--" if not found.
   */
  public static String resolveCredential(Map<String, String> properties) {
    return resolveCredential(properties, null);
  }

  private static String resolveCredential(
      Map<String, String> properties, @Nullable String credentialProviders) {
    if (properties == null || properties.isEmpty()) {
      return firstCredentialProvider(credentialProviders);
    }

    String resolvedCredential = firstCredentialProvider(credentialProviders);
    if (!DEFAULT_VALUE.equals(resolvedCredential)) {
      return resolvedCredential;
    }
    resolvedCredential =
        firstCredentialProvider(properties.get(CredentialConstants.CREDENTIAL_PROVIDERS));
    if (!DEFAULT_VALUE.equals(resolvedCredential)) {
      return resolvedCredential;
    }

    if (usesCommonKerberosProperties(properties) || usesHiveKerberosProperties(properties)) {
      return "kerberos-keytab";
    }

    if (StringUtils.isNotBlank(getProperty(properties, S3Properties.GRAVITINO_S3_ACCESS_KEY_ID))
        || StringUtils.isNotBlank(
            getProperty(properties, S3Properties.GRAVITINO_S3_SECRET_ACCESS_KEY))) {
      return "s3-secret-key";
    }
    if (StringUtils.isNotBlank(getProperty(properties, OSSProperties.GRAVITINO_OSS_ACCESS_KEY_ID))
        || StringUtils.isNotBlank(
            getProperty(properties, OSSProperties.GRAVITINO_OSS_ACCESS_KEY_SECRET))) {
      return "oss-secret-key";
    }
    if (StringUtils.isNotBlank(
        getProperty(properties, AzureProperties.GRAVITINO_AZURE_STORAGE_ACCOUNT_KEY))) {
      return "azure-account-key";
    }
    if (StringUtils.isNotBlank(
            getProperty(properties, GCSProperties.GRAVITINO_GCS_SERVICE_ACCOUNT_FILE))
        || StringUtils.isNotBlank(getProperty(properties, "gcs-credential-file-path"))) {
      return "gcs-token";
    }
    if (StringUtils.isNotBlank(getProperty(properties, "jdbc-user"))
        || StringUtils.isNotBlank(getProperty(properties, "jdbc-password"))) {
      return "jdbc-user-password";
    }

    return DEFAULT_VALUE;
  }

  private static String firstCredentialProvider(@Nullable String credentialProviders) {
    if (StringUtils.isNotBlank(credentialProviders)) {
      for (String credentialProvider : credentialProviders.split(",")) {
        if (StringUtils.isNotBlank(credentialProvider)) {
          return credentialProvider.trim();
        }
      }
    }
    return DEFAULT_VALUE;
  }

  private static String getProperty(Map<String, String> properties, String... keys) {
    if (properties == null || properties.isEmpty() || keys == null) {
      return null;
    }
    for (String key : keys) {
      String value = properties.get(key);
      if (StringUtils.isNotBlank(value)) {
        return value;
      }
    }
    return null;
  }

  private static String resolveJdbcEndpoint(String provider, Map<String, String> properties) {
    return resolveJdbcEndpoint(
        provider, getProperty(properties, "jdbc-url"), getProperty(properties, "jdbc-driver"));
  }

  private static String resolveJdbcEndpoint(
      String provider, String jdbcUrl, @Nullable String driver) {
    if (StringUtils.isBlank(jdbcUrl)) {
      return DEFAULT_VALUE;
    }

    String trimmedUrl = jdbcUrl.trim();
    String lowerUrl = trimmedUrl.toLowerCase(Locale.ROOT);
    if (!lowerUrl.startsWith(JDBC_PREFIX)) {
      return DEFAULT_VALUE;
    }

    if (isBigQueryJdbc(provider, driver, lowerUrl)) {
      return sanitizeBigQueryJdbcEndpoint(trimmedUrl);
    }
    if (isOracleJdbc(provider, driver, lowerUrl)) {
      return sanitizeOracleJdbcEndpoint(trimmedUrl);
    }
    return sanitizeHierarchicalJdbcEndpoint(trimmedUrl);
  }

  private static boolean isBigQueryJdbc(String provider, String driver, String lowerUrl) {
    return "jdbc-bigquery".equals(provider)
        || lowerUrl.startsWith("jdbc:bigquery:")
        || StringUtils.containsIgnoreCase(driver, "bigquery");
  }

  private static boolean isOracleJdbc(String provider, String driver, String lowerUrl) {
    return "jdbc-oracle".equals(provider)
        || lowerUrl.startsWith(ORACLE_THIN_JDBC_PREFIX)
        || StringUtils.containsIgnoreCase(driver, "oracle");
  }

  private static String sanitizeBigQueryJdbcEndpoint(String jdbcUrl) {
    String baseUrl = stripJdbcProperties(jdbcUrl);
    if (!startsWithIgnoreCase(baseUrl, BIGQUERY_JDBC_PREFIX)) {
      return DEFAULT_VALUE;
    }

    String nestedEndpoint = baseUrl.substring(BIGQUERY_JDBC_PREFIX.length());
    if (startsWithIgnoreCase(nestedEndpoint, "http://")
        || startsWithIgnoreCase(nestedEndpoint, "https://")) {
      String sanitizedEndpoint = sanitizeHierarchicalUri(nestedEndpoint);
      return DEFAULT_VALUE.equals(sanitizedEndpoint)
          ? DEFAULT_VALUE
          : BIGQUERY_JDBC_PREFIX + sanitizedEndpoint;
    }

    String sanitizedEndpoint = sanitizeHierarchicalUri("bigquery://" + nestedEndpoint);
    return DEFAULT_VALUE.equals(sanitizedEndpoint)
        ? DEFAULT_VALUE
        : JDBC_PREFIX + sanitizedEndpoint;
  }

  private static String sanitizeOracleJdbcEndpoint(String jdbcUrl) {
    String baseUrl = stripJdbcProperties(jdbcUrl);
    if (!startsWithIgnoreCase(baseUrl, ORACLE_THIN_JDBC_PREFIX)) {
      return DEFAULT_VALUE;
    }

    String connectionTarget = baseUrl.substring(ORACLE_THIN_JDBC_PREFIX.length());
    int atIndex = connectionTarget.lastIndexOf('@');
    if (atIndex < 0 || atIndex == connectionTarget.length() - 1) {
      return DEFAULT_VALUE;
    }

    String endpoint = connectionTarget.substring(atIndex + 1);
    if (endpoint.startsWith("(")) {
      return sanitizeOracleTnsDescriptor(endpoint);
    }
    if (endpoint.startsWith("//")) {
      String sanitizedEndpoint = sanitizeHierarchicalUri("oracle:" + endpoint);
      return DEFAULT_VALUE.equals(sanitizedEndpoint)
          ? DEFAULT_VALUE
          : ORACLE_THIN_JDBC_PREFIX + "@" + sanitizedEndpoint.substring("oracle:".length());
    }

    return isSafeOracleTarget(endpoint) ? ORACLE_THIN_JDBC_PREFIX + "@" + endpoint : DEFAULT_VALUE;
  }

  private static String sanitizeOracleTnsDescriptor(String descriptor) {
    String trimmedDescriptor = descriptor.trim();
    Matcher descriptionMatcher = ORACLE_TNS_DESCRIPTION_PATTERN.matcher(trimmedDescriptor);
    if (!descriptionMatcher.find()
        || findMatchingOracleTnsParenthesis(trimmedDescriptor, 0)
            != trimmedDescriptor.length() - 1) {
      return DEFAULT_VALUE;
    }

    List<OracleTnsAddress> addresses = new ArrayList<>();
    for (String addressDescriptor :
        findOracleTnsSections(trimmedDescriptor, ORACLE_TNS_ADDRESS_PATTERN)) {
      String protocol = findOracleTnsValue(addressDescriptor, ORACLE_TNS_PROTOCOL_PATTERN);
      String host = findOracleTnsValue(addressDescriptor, ORACLE_TNS_HOST_PATTERN);
      String port = findOracleTnsValue(addressDescriptor, ORACLE_TNS_PORT_PATTERN);
      if ((protocol != null && !isSafeOracleProtocol(protocol))
          || !isSafeOracleHost(host)
          || (port != null && !isSafeOraclePort(port))) {
        return DEFAULT_VALUE;
      }
      addresses.add(
          new OracleTnsAddress(
              protocol == null ? null : protocol.trim().toUpperCase(Locale.ROOT),
              host.trim().toLowerCase(Locale.ROOT),
              port == null ? null : port.trim()));
    }
    if (addresses.isEmpty()) {
      return DEFAULT_VALUE;
    }

    String serviceName = findOracleTnsValue(trimmedDescriptor, ORACLE_TNS_SERVICE_NAME_PATTERN);
    String connectIdentifierKey = "SERVICE_NAME";
    if (!isSafeOracleConnectIdentifier(serviceName)) {
      serviceName = findOracleTnsValue(trimmedDescriptor, ORACLE_TNS_SID_PATTERN);
      connectIdentifierKey = "SID";
    }
    if (!isSafeOracleConnectIdentifier(serviceName)) {
      return DEFAULT_VALUE;
    }

    StringBuilder sanitized = new StringBuilder(ORACLE_THIN_JDBC_PREFIX).append("@(DESCRIPTION=");
    for (OracleTnsAddress address : addresses) {
      sanitized.append("(ADDRESS=");
      if (address.protocol != null) {
        sanitized.append("(PROTOCOL=").append(address.protocol).append(')');
      }
      sanitized.append("(HOST=").append(address.host).append(')');
      if (address.port != null) {
        sanitized.append("(PORT=").append(address.port).append(')');
      }
      sanitized.append(')');
    }
    return sanitized
        .append("(CONNECT_DATA=(")
        .append(connectIdentifierKey)
        .append('=')
        .append(serviceName.trim())
        .append(")))")
        .toString();
  }

  private static List<String> findOracleTnsSections(String descriptor, Pattern sectionPattern) {
    List<String> sections = new ArrayList<>();
    Matcher matcher = sectionPattern.matcher(descriptor);
    int searchFrom = 0;
    while (matcher.find(searchFrom)) {
      int sectionEnd = findMatchingOracleTnsParenthesis(descriptor, matcher.start());
      if (sectionEnd < 0) {
        return new ArrayList<>();
      }
      sections.add(descriptor.substring(matcher.start(), sectionEnd + 1));
      searchFrom = sectionEnd + 1;
    }
    return sections;
  }

  private static int findMatchingOracleTnsParenthesis(String descriptor, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < descriptor.length(); i++) {
      if (descriptor.charAt(i) == '(') {
        depth++;
      } else if (descriptor.charAt(i) == ')' && --depth == 0) {
        return i;
      }
      if (depth < 0) {
        return -1;
      }
    }
    return -1;
  }

  @Nullable
  private static String findOracleTnsValue(String descriptor, Pattern valuePattern) {
    Matcher matcher = valuePattern.matcher(descriptor);
    return matcher.find() ? matcher.group(1).trim() : null;
  }

  private static boolean isSafeOracleHost(@Nullable String host) {
    return StringUtils.isNotBlank(host) && hasOnlyOracleTargetCharacters(host.trim());
  }

  private static boolean isSafeOracleProtocol(String protocol) {
    return "TCP".equalsIgnoreCase(protocol.trim()) || "TCPS".equalsIgnoreCase(protocol.trim());
  }

  private static boolean isSafeOraclePort(@Nullable String port) {
    if (StringUtils.isBlank(port)) {
      return false;
    }
    try {
      int parsedPort = Integer.parseInt(port.trim());
      return parsedPort > 0 && parsedPort <= 65535;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean isSafeOracleConnectIdentifier(@Nullable String identifier) {
    if (StringUtils.isBlank(identifier)) {
      return false;
    }
    for (int i = 0; i < identifier.trim().length(); i++) {
      char character = identifier.trim().charAt(i);
      if (!Character.isLetterOrDigit(character)
          && character != '.'
          && character != '-'
          && character != '_'
          && character != '$'
          && character != '#') {
        return false;
      }
    }
    return true;
  }

  private static boolean isSafeOracleTarget(String endpoint) {
    return StringUtils.isNotBlank(endpoint) && hasOnlyOracleTargetCharacters(endpoint);
  }

  private static boolean hasOnlyOracleTargetCharacters(String value) {
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      if (!Character.isLetterOrDigit(character)
          && character != '.'
          && character != '-'
          && character != '_'
          && character != ':'
          && character != '['
          && character != ']') {
        return false;
      }
    }
    return true;
  }

  private static String sanitizeHierarchicalJdbcEndpoint(String jdbcUrl) {
    String baseUrl = stripJdbcProperties(jdbcUrl);
    if (!startsWithIgnoreCase(baseUrl, JDBC_PREFIX)) {
      return DEFAULT_VALUE;
    }

    String sanitizedEndpoint = sanitizeHierarchicalUri(baseUrl.substring(JDBC_PREFIX.length()));
    return DEFAULT_VALUE.equals(sanitizedEndpoint)
        ? DEFAULT_VALUE
        : JDBC_PREFIX + sanitizedEndpoint;
  }

  private static String sanitizeHierarchicalUri(String endpoint) {
    try {
      URI uri = URI.create(endpoint);
      String authority = sanitizeAuthority(uri);
      if (StringUtils.isBlank(uri.getScheme()) || DEFAULT_VALUE.equals(authority)) {
        return DEFAULT_VALUE;
      }

      StringBuilder builder = new StringBuilder();
      builder.append(uri.getScheme()).append("://").append(authority);
      if (uri.getRawPath() != null) {
        builder.append(stripUriPathParameters(uri.getRawPath()));
      }
      return builder.toString();
    } catch (IllegalArgumentException e) {
      return DEFAULT_VALUE;
    }
  }

  private static String sanitizeUriEndpoint(String endpoint) {
    if (StringUtils.isBlank(endpoint)) {
      return DEFAULT_VALUE;
    }

    String trimmedEndpoint = endpoint.trim();
    String sanitized = sanitizeHierarchicalUri(trimmedEndpoint);
    if (!DEFAULT_VALUE.equals(sanitized)) {
      return sanitized;
    }

    try {
      URI uri = URI.create(trimmedEndpoint);
      if (StringUtils.isBlank(uri.getScheme())
          || uri.isOpaque()
          || uri.getRawAuthority() != null
          || StringUtils.isBlank(uri.getRawPath())) {
        return DEFAULT_VALUE;
      }
      String prefix =
          startsWithIgnoreCase(trimmedEndpoint, uri.getScheme() + "://")
              ? uri.getScheme() + "://"
              : uri.getScheme() + ":";
      return prefix + stripUriPathParameters(uri.getRawPath());
    } catch (IllegalArgumentException e) {
      return DEFAULT_VALUE;
    }
  }

  private static String stripUriPathParameters(String rawPath) {
    StringBuilder sanitized = new StringBuilder(rawPath.length());
    boolean inParameter = false;
    for (int index = 0; index < rawPath.length(); index++) {
      char character = rawPath.charAt(index);
      if (character == ';') {
        inParameter = true;
      } else if (character == '/') {
        inParameter = false;
        sanitized.append(character);
      } else if (!inParameter) {
        sanitized.append(character);
      }
    }
    return sanitized.toString();
  }

  private static String sanitizeFilesystemLocation(String location) {
    if (StringUtils.isBlank(location)) {
      return DEFAULT_VALUE;
    }

    String trimmedLocation = location.trim();
    String sanitized = sanitizeUriEndpoint(trimmedLocation);
    if (!DEFAULT_VALUE.equals(sanitized)) {
      return sanitized;
    }

    try {
      URI uri = URI.create(trimmedLocation);
      if (uri.getScheme() != null
          || uri.isOpaque()
          || uri.getRawAuthority() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || StringUtils.isBlank(uri.getRawPath())) {
        return DEFAULT_VALUE;
      }
      return uri.getRawPath();
    } catch (IllegalArgumentException e) {
      return DEFAULT_VALUE;
    }
  }

  private static String sanitizeUriListEndpoint(String endpoint) {
    if (StringUtils.isBlank(endpoint)) {
      return DEFAULT_VALUE;
    }

    StringJoiner sanitizedEndpoints = new StringJoiner(",");
    for (String candidate : endpoint.split(",", -1)) {
      String sanitized = sanitizeUriEndpoint(candidate);
      if (DEFAULT_VALUE.equals(sanitized)) {
        return DEFAULT_VALUE;
      }
      sanitizedEndpoints.add(sanitized);
    }
    return sanitizedEndpoints.toString();
  }

  private static String sanitizeAuthorityListEndpoint(String endpoint) {
    if (StringUtils.isBlank(endpoint)) {
      return DEFAULT_VALUE;
    }

    StringJoiner sanitizedAuthorities = new StringJoiner(",");
    for (String authority : endpoint.split(",", -1)) {
      try {
        URI uri = URI.create("//" + authority.trim());
        if (StringUtils.isNotBlank(uri.getRawPath()) && !"/".equals(uri.getRawPath())) {
          return DEFAULT_VALUE;
        }

        String sanitizedAuthority = sanitizeAuthority(uri);
        if (DEFAULT_VALUE.equals(sanitizedAuthority)) {
          return DEFAULT_VALUE;
        }
        sanitizedAuthorities.add(sanitizedAuthority);
      } catch (IllegalArgumentException e) {
        return DEFAULT_VALUE;
      }
    }
    return sanitizedAuthorities.toString();
  }

  private static String sanitizeAuthority(URI uri) {
    String host = uri.getHost();
    if (StringUtils.isNotBlank(host)) {
      StringBuilder builder = new StringBuilder();
      if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
        builder.append('[').append(host).append(']');
      } else {
        builder.append(host);
      }
      if (uri.getPort() >= 0) {
        builder.append(':').append(uri.getPort());
      }
      return builder.toString();
    }

    String rawAuthority = uri.getRawAuthority();
    if (StringUtils.isBlank(rawAuthority)) {
      return DEFAULT_VALUE;
    }

    int userInfoEnd = rawAuthority.lastIndexOf('@');
    String authority = rawAuthority.substring(userInfoEnd + 1);
    Matcher matcher = SAFE_RAW_AUTHORITY_PATTERN.matcher(authority);
    if (!matcher.matches()) {
      return DEFAULT_VALUE;
    }
    return authority;
  }

  private static String stripJdbcProperties(String jdbcUrl) {
    int endIndex = jdbcUrl.length();
    for (char delimiter : new char[] {';', '?', '#'}) {
      int delimiterIndex = jdbcUrl.indexOf(delimiter);
      if (delimiterIndex >= 0) {
        endIndex = Math.min(endIndex, delimiterIndex);
      }
    }
    return jdbcUrl.substring(0, endIndex);
  }

  private static String normalizeEndpoint(String endpoint) {
    String trimmedEndpoint = endpoint.trim();
    if (startsWithIgnoreCase(trimmedEndpoint, BIGQUERY_JDBC_PREFIX + "http://")
        || startsWithIgnoreCase(trimmedEndpoint, BIGQUERY_JDBC_PREFIX + "https://")) {
      String nestedEndpoint = trimmedEndpoint.substring(BIGQUERY_JDBC_PREFIX.length());
      return BIGQUERY_JDBC_PREFIX + normalizeUriEndpoint(nestedEndpoint);
    }
    if (startsWithIgnoreCase(trimmedEndpoint, JDBC_PREFIX)) {
      return JDBC_PREFIX + normalizeUriEndpoint(trimmedEndpoint.substring(JDBC_PREFIX.length()));
    }
    return normalizeUriEndpoint(trimmedEndpoint);
  }

  private static String normalizeUriEndpoint(String endpoint) {
    String normalizedAuthorities = normalizeAuthorityList(endpoint);
    if (normalizedAuthorities != null) {
      return normalizedAuthorities;
    }

    try {
      URI uri = URI.create(endpoint);
      if (StringUtils.isNotBlank(uri.getScheme()) && StringUtils.isNotBlank(uri.getHost())) {
        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://");
        if (uri.getRawUserInfo() != null) {
          builder.append(uri.getRawUserInfo()).append('@');
        }
        builder.append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() >= 0) {
          builder.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
          builder.append(RESTUtils.stripTrailingSlash(uri.getRawPath()));
        }
        if (uri.getRawQuery() != null) {
          builder.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
          builder.append('#').append(uri.getRawFragment());
        }
        return builder.toString();
      }
      if (StringUtils.isNotBlank(uri.getScheme()) && uri.isOpaque()) {
        String normalizedEndpoint =
            uri.getScheme().toLowerCase(Locale.ROOT) + ":" + uri.getRawSchemeSpecificPart();
        if (uri.getRawFragment() != null) {
          normalizedEndpoint += "#" + uri.getRawFragment();
        }
        return endpoint.indexOf('?') >= 0 || endpoint.indexOf('#') >= 0
            ? normalizedEndpoint
            : RESTUtils.stripTrailingSlash(normalizedEndpoint);
      }
    } catch (IllegalArgumentException e) {
      // Fall through to the authority-list and conservative fallback handling.
    }

    return endpoint.indexOf('?') >= 0 || endpoint.indexOf('#') >= 0
        ? endpoint
        : RESTUtils.stripTrailingSlash(endpoint);
  }

  @Nullable
  private static String normalizeAuthorityList(String endpoint) {
    StringJoiner normalizedAuthorities = new StringJoiner(",");
    for (String authority : endpoint.split(",", -1)) {
      try {
        URI uri = URI.create("//" + authority.trim());
        if (StringUtils.isBlank(uri.getHost())
            || (StringUtils.isNotBlank(uri.getRawPath()) && !"/".equals(uri.getRawPath()))
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null) {
          return null;
        }

        StringBuilder builder = new StringBuilder();
        if (uri.getRawUserInfo() != null) {
          builder.append(uri.getRawUserInfo()).append('@');
        }
        builder.append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() >= 0) {
          builder.append(':').append(uri.getPort());
        }
        normalizedAuthorities.add(builder.toString());
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return normalizedAuthorities.toString();
  }

  private static boolean startsWithIgnoreCase(String value, String prefix) {
    return value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  private static String resolveGlueEndpoint(Map<String, String> properties) {
    String endpoint = getProperty(properties, GlueConstants.AWS_GLUE_ENDPOINT);
    if (StringUtils.isNotBlank(endpoint)) {
      String sanitized = sanitizeUriEndpoint(endpoint);
      return DEFAULT_VALUE.equals(sanitized) ? sanitizeAuthorityListEndpoint(endpoint) : sanitized;
    }

    String region = getProperty(properties, GlueConstants.AWS_REGION);
    return StringUtils.isNotBlank(region)
        ? sanitizeAuthorityListEndpoint(String.format("glue.%s.amazonaws.com", region.trim()))
        : DEFAULT_VALUE;
  }

  private static boolean usesCommonKerberosProperties(Map<String, String> properties) {
    return StringUtils.equalsIgnoreCase(getProperty(properties, "authentication.type"), "kerberos")
        || StringUtils.isNotBlank(getProperty(properties, "authentication.kerberos.principal"))
        || StringUtils.isNotBlank(getProperty(properties, "authentication.kerberos.keytab-uri"));
  }

  private static boolean usesHiveKerberosProperties(Map<String, String> properties) {
    return StringUtils.isNotBlank(getProperty(properties, HiveConstants.PRINCIPAL))
        || StringUtils.isNotBlank(getProperty(properties, HiveConstants.KEY_TAB_URI))
        || Boolean.parseBoolean(
            getProperty(properties, "gravitino.bypass.hive.metastore.sasl.enabled"));
  }

  private static String toTitleCase(String input) {
    if (StringUtils.isBlank(input)) {
      return "";
    }
    String[] words = input.trim().split("[-_\\s]+");
    StringBuilder sb = new StringBuilder();
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(" ");
      }
      sb.append(Character.toUpperCase(word.charAt(0)));
      if (word.length() > 1) {
        sb.append(word.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return sb.toString();
  }

  private static final class OracleTnsAddress {
    @Nullable private final String protocol;
    private final String host;
    @Nullable private final String port;

    private OracleTnsAddress(@Nullable String protocol, String host, @Nullable String port) {
      this.protocol = protocol;
      this.host = host;
      this.port = port;
    }
  }
}
