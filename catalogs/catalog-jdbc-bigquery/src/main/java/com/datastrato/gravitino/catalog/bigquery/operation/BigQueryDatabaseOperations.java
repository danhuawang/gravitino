/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery.operation;

import com.datastrato.gravitino.catalog.bigquery.BigQueryClientPool;
import com.datastrato.gravitino.catalog.bigquery.BigQuerySchemaPropertiesMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.DatasetList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.meta.AuditInfo;

/** Database operations for BigQuery. In BigQuery, databases are called datasets. */
public class BigQueryDatabaseOperations extends JdbcDatabaseOperations {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private BigQueryClientPool clientPool;

  /**
   * Sets the BigQuery API client pool.
   *
   * @param clientPool BigQuery client pool
   */
  public void setClientPool(BigQueryClientPool clientPool) {
    this.clientPool = clientPool;
  }

  /**
   * Lists all datasets (databases) in the BigQuery project using BigQuery API.
   *
   * <p>This method uses the BigQuery API instead of INFORMATION_SCHEMA to avoid regional
   * limitations. INFORMATION_SCHEMA is region-specific and cannot list datasets across all regions
   * in a single query.
   *
   * @return list of dataset names
   */
  @Override
  public List<String> listDatabases() {
    List<String> databaseNames = new ArrayList<>();

    // Use BigQuery API - required for cross-region dataset listing
    BigQueryOperationUtils.BigQueryContext context =
        BigQueryOperationUtils.getBigQueryContext(clientPool, "list datasets");

    try {
      LOG.debug("Listing BigQuery datasets using API for project: {}", context.getProjectId());

      // List all datasets in the project using BigQuery API
      Bigquery.Datasets.List request =
          context.getBigquery().datasets().list(context.getProjectId());
      request.setAll(false); // Only list visible datasets (not hidden or temporary)

      DatasetList response = request.execute();

      if (response.getDatasets() != null) {
        for (DatasetList.Datasets dataset : response.getDatasets()) {
          String datasetId = dataset.getDatasetReference().getDatasetId();
          if (!isSystemDatabase(datasetId)) {
            databaseNames.add(datasetId);
            LOG.debug("Found BigQuery dataset: {}", datasetId);
          }
        }
      }

      LOG.info(
          "Listed {} BigQuery datasets using API for project: {}",
          databaseNames.size(),
          context.getProjectId());
      return databaseNames;

    } catch (IOException e) {
      LOG.error("Failed to list BigQuery datasets using API", e);
      throw new RuntimeException("Failed to list BigQuery datasets using API", e);
    }
  }

  /**
   * Checks if a dataset exists using BigQuery API.
   *
   * <p>This method uses the BigQuery API instead of JDBC to avoid regional limitations. JDBC
   * connections cannot access datasets in different regions.
   *
   * @param databaseName the dataset name to check
   * @return true if the dataset exists, false otherwise
   */
  @Override
  public boolean exist(String databaseName) {
    // Use BigQuery API - required for cross-region dataset access
    BigQueryOperationUtils.BigQueryContext context =
        BigQueryOperationUtils.getBigQueryContext(clientPool, "check dataset existence");

    try {
      LOG.debug("Checking dataset existence using API: {}", databaseName);

      // Try to get the dataset using BigQuery API
      context.getBigquery().datasets().get(context.getProjectId(), databaseName).execute();

      LOG.debug("Dataset exists: {}", databaseName);
      return true;

    } catch (IOException e) {
      // 404 means dataset doesn't exist
      if (e.getMessage() != null && e.getMessage().contains("404")) {
        LOG.debug("Dataset does not exist: {}", databaseName);
        return false;
      }
      // Other errors should be thrown
      LOG.error("Failed to check dataset existence using API: {}", databaseName, e);
      throw new RuntimeException(
          "Failed to check dataset existence using BigQuery API: " + databaseName, e);
    }
  }

  /**
   * Loads a dataset's metadata using BigQuery API.
   *
   * <p>This method uses the BigQuery API to retrieve dataset information including all available
   * properties. All property values are returned as strings for consistency.
   *
   * @param databaseName the dataset name to load
   * @return JdbcSchema object containing dataset metadata
   * @throws NoSuchSchemaException if the dataset does not exist
   */
  @Override
  public JdbcSchema load(String databaseName) throws NoSuchSchemaException {
    // Use BigQuery API - required for cross-region dataset access and complete metadata
    BigQueryOperationUtils.BigQueryContext context =
        BigQueryOperationUtils.getBigQueryContext(clientPool, "load dataset");

    try {
      LOG.debug("Loading dataset using API: {}", databaseName);

      // Get dataset details using BigQuery API
      Dataset dataset =
          context.getBigquery().datasets().get(context.getProjectId(), databaseName).execute();

      // Extract all available properties from dataset
      Map<String, String> properties = extractDatasetProperties(dataset);

      LOG.info(
          "Loaded dataset using API: {} in location: {} with {} properties",
          databaseName,
          dataset.getLocation(),
          properties.size());

      return JdbcSchema.builder()
          .withName(databaseName)
          .withComment(dataset.getDescription())
          .withProperties(properties)
          .withAuditInfo(AuditInfo.EMPTY)
          .build();

    } catch (IOException e) {
      // 404 means dataset doesn't exist
      if (e.getMessage() != null && e.getMessage().contains("404")) {
        LOG.warn("Dataset not found: {}", databaseName);
        throw new NoSuchSchemaException("Database %s could not be found", databaseName);
      }
      // Other errors should be thrown
      LOG.error("Failed to load dataset using API: {}", databaseName, e);
      throw new RuntimeException("Failed to load dataset using BigQuery API: " + databaseName, e);
    }
  }

  /**
   * Extracts all available properties from a BigQuery dataset and converts them to string values.
   *
   * @param dataset the BigQuery dataset object
   * @return map of property names to string values
   */
  private Map<String, String> extractDatasetProperties(Dataset dataset) {
    Map<String, String> properties = new HashMap<>();

    // Location
    if (dataset.getLocation() != null) {
      properties.put(BigQuerySchemaPropertiesMetadata.LOCATION, dataset.getLocation());
    }

    // Description
    if (dataset.getDescription() != null) {
      properties.put(BigQuerySchemaPropertiesMetadata.DESCRIPTION, dataset.getDescription());
    }

    // Friendly name
    if (dataset.getFriendlyName() != null) {
      properties.put(BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME, dataset.getFriendlyName());
    }

    // Default table expiration (convert from milliseconds to days)
    if (dataset.getDefaultTableExpirationMs() != null) {
      double expirationDays = dataset.getDefaultTableExpirationMs() / (1000.0 * 60 * 60 * 24);
      properties.put(
          BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS,
          String.valueOf(expirationDays));
    }

    // Default partition expiration (convert from milliseconds to days)
    if (dataset.getDefaultPartitionExpirationMs() != null) {
      double expirationDays = dataset.getDefaultPartitionExpirationMs() / (1000.0 * 60 * 60 * 24);
      properties.put(
          BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS,
          String.valueOf(expirationDays));
    }

    // Storage billing model
    if (dataset.getStorageBillingModel() != null) {
      properties.put(
          BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL, dataset.getStorageBillingModel());
    }

    // Max time travel hours (convert from milliseconds to hours)
    if (dataset.getMaxTimeTravelHours() != null) {
      properties.put(
          BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS,
          String.valueOf(dataset.getMaxTimeTravelHours()));
    }

    // Default KMS key name
    if (dataset.getDefaultEncryptionConfiguration() != null
        && dataset.getDefaultEncryptionConfiguration().getKmsKeyName() != null) {
      properties.put(
          BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME,
          dataset.getDefaultEncryptionConfiguration().getKmsKeyName());
    }

    // Default rounding mode
    if (dataset.getDefaultRoundingMode() != null) {
      properties.put(
          BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE, dataset.getDefaultRoundingMode());
    }

    // Case insensitive setting
    if (dataset.getIsCaseInsensitive() != null) {
      properties.put(
          BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE,
          String.valueOf(dataset.getIsCaseInsensitive()));
    }

    // Labels (convert to JSON-like string representation)
    if (dataset.getLabels() != null && !dataset.getLabels().isEmpty()) {
      StringBuilder labelsJson = new StringBuilder("[");
      boolean first = true;
      for (Map.Entry<String, String> label : dataset.getLabels().entrySet()) {
        if (!first) {
          labelsJson.append(", ");
        }
        first = false;
        labelsJson
            .append("{\"")
            .append(BigQueryOperationUtils.escapeString(label.getKey()))
            .append("\":\"")
            .append(BigQueryOperationUtils.escapeString(label.getValue()))
            .append("\"}");
      }
      labelsJson.append("]");
      properties.put(BigQuerySchemaPropertiesMetadata.LABELS, labelsJson.toString());
    }

    // Cross-region replication properties (if applicable)
    // Note: These properties may not be available in all BigQuery API versions
    // They are typically managed through separate replication APIs

    // Default collation (if available)
    // Note: This property may not be directly available through the Dataset API
    // It's typically set during dataset creation and may require separate queries

    LOG.debug("Extracted {} properties from dataset: {}", properties.size(), properties.keySet());
    return properties;
  }

  @Override
  protected String generateDatabaseExistSql(String databaseName) {
    // In BigQuery, we need to query INFORMATION_SCHEMA.SCHEMATA without setting schema context
    // to avoid the "my_dataset.information_schema" issue
    // Note: This method is not used in practice as we override exist() to use BigQuery API
    // However, we escape wildcards to prevent SQL injection if this method is ever called
    String escapedName = databaseName.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    return String.format(
        "SELECT schema_name FROM INFORMATION_SCHEMA.SCHEMATA WHERE schema_name = '%s'",
        escapedName);
  }

  @Override
  public String generateDropDatabaseSql(String databaseName, boolean cascade) {
    // BigQuery uses DROP SCHEMA syntax, not DROP DATABASE
    if (cascade) {
      return String.format("DROP SCHEMA `%s` CASCADE", databaseName);
    } else {
      return String.format("DROP SCHEMA `%s` RESTRICT", databaseName);
    }
  }

  @Override
  protected boolean supportSchemaComment() {
    return true; // BigQuery supports dataset descriptions via OPTIONS
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return ImmutableSet.of("information_schema", "INFORMATION_SCHEMA");
  }

  /**
   * Generates CREATE SCHEMA (Dataset) SQL for BigQuery.
   *
   * <p>BigQuery syntax: CREATE SCHEMA [IF NOT EXISTS] dataset_name [DEFAULT COLLATE
   * collate_specification] [OPTIONS(schema_option_list)]
   *
   * @param databaseName the dataset name
   * @param comment the dataset description
   * @param properties BigQuery-specific dataset properties
   * @return the CREATE SCHEMA SQL statement
   */
  @Override
  public String generateCreateDatabaseSql(
      String databaseName, String comment, Map<String, String> properties) {

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("CREATE SCHEMA ");

    // Dataset name with backticks for safety
    sqlBuilder.append("`").append(databaseName).append("`");

    // Default collation (immutable, only at creation)
    if (properties != null
        && properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_COLLATION)) {
      String collation = properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_COLLATION);
      if (StringUtils.isNotEmpty(collation)) {
        sqlBuilder.append("\nDEFAULT COLLATE '").append(collation).append("'");
      }
    }

    // OPTIONS clause
    String optionsClause = generateSchemaOptionsClause(comment, properties);
    if (!optionsClause.isEmpty()) {
      sqlBuilder.append("\n").append(optionsClause);
    }

    String result = sqlBuilder.append(";").toString();
    LOG.info("Generated CREATE SCHEMA SQL for dataset '{}': {}", databaseName, result);
    return result;
  }

  /**
   * Generates the OPTIONS clause for CREATE SCHEMA with all supported BigQuery properties.
   *
   * @param comment the dataset description
   * @param properties BigQuery-specific dataset properties
   * @return the OPTIONS clause, or empty string if no options
   */
  private String generateSchemaOptionsClause(String comment, Map<String, String> properties) {
    List<String> options = new ArrayList<>();

    // Description from comment parameter
    if (StringUtils.isNotEmpty(comment)) {
      options.add(
          String.format("description=\"%s\"", BigQueryOperationUtils.escapeString(comment)));
    }

    if (properties != null) {
      // Location (immutable, only at creation)
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.LOCATION)) {
        String location = properties.get(BigQuerySchemaPropertiesMetadata.LOCATION);
        if (StringUtils.isNotEmpty(location)) {
          options.add(String.format("location=\"%s\"", location));
        }
      }

      // Default table expiration days
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS)) {
        String value =
            properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("default_table_expiration_days=%s", value));
        }
      }

      // Default partition expiration days
      if (properties.containsKey(
          BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS)) {
        String value =
            properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("default_partition_expiration_days=%s", value));
        }
      }

      // Case insensitive setting
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("is_case_insensitive=%s", value));
        }
      }

      // Storage billing model
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("storage_billing_model=\"%s\"", value));
        }
      }

      // Max time travel hours
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("max_time_travel_hours=%s", value));
        }
      }

      // Default KMS key name
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("default_kms_key_name=\"%s\"", value));
        }
      }

      // Default rounding mode
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("default_rounding_mode=\"%s\"", value));
        }
      }

      // Friendly name
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME);
        if (StringUtils.isNotEmpty(value)) {
          options.add(
              String.format("friendly_name=\"%s\"", BigQueryOperationUtils.escapeString(value)));
        }
      }

      // Failover reservation
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.FAILOVER_RESERVATION)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.FAILOVER_RESERVATION);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("failover_reservation=\"%s\"", value));
        }
      }

      // Is primary replica
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.IS_PRIMARY)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.IS_PRIMARY);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("is_primary=%s", value));
        }
      }

      // Primary replica name
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.PRIMARY_REPLICA)) {
        String value = properties.get(BigQuerySchemaPropertiesMetadata.PRIMARY_REPLICA);
        if (StringUtils.isNotEmpty(value)) {
          options.add(String.format("primary_replica=\"%s\"", value));
        }
      }

      // Labels (expect JSON array format)
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.LABELS)) {
        String labels = properties.get(BigQuerySchemaPropertiesMetadata.LABELS);
        if (StringUtils.isNotEmpty(labels)) {
          String labelsClause = convertLabelsToSqlFormat(labels);
          if (StringUtils.isNotEmpty(labelsClause)) {
            options.add(String.format("labels=%s", labelsClause));
          }
        }
      }

      // IAM Tags (expect JSON array format)
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.TAGS)) {
        String tags = properties.get(BigQuerySchemaPropertiesMetadata.TAGS);
        if (StringUtils.isNotEmpty(tags)) {
          String tagsClause = convertTagsToSqlFormat(tags);
          if (StringUtils.isNotEmpty(tagsClause)) {
            options.add(String.format("tags=%s", tagsClause));
          }
        }
      }

      // Description from properties (overrides comment if both present)
      if (properties.containsKey(BigQuerySchemaPropertiesMetadata.DESCRIPTION)) {
        String description = properties.get(BigQuerySchemaPropertiesMetadata.DESCRIPTION);
        if (StringUtils.isNotEmpty(description)) {
          // Remove description from comment if it was already added
          options.removeIf(option -> option.startsWith("description="));
          options.add(
              String.format(
                  "description=\"%s\"", BigQueryOperationUtils.escapeString(description)));
        }
      }
    }

    if (options.isEmpty()) {
      return "";
    }

    return "OPTIONS(\n  " + String.join(",\n  ", options) + "\n)";
  }

  /**
   * Converts JSON array format labels to BigQuery SQL format.
   *
   * <p>Input: [{"key1":"value1"},{"key2":"value2"}] Output: [("key1","value1"),("key2","value2")]
   *
   * <p>Uses Jackson for robust JSON parsing with proper error handling.
   *
   * @param labelsJson JSON array string
   * @return BigQuery SQL format labels string
   */
  private String convertLabelsToSqlFormat(String labelsJson) {
    if (StringUtils.isBlank(labelsJson)) {
      return "";
    }

    try {
      // Parse JSON array of label objects using Jackson
      List<Map<String, String>> labelsList =
          OBJECT_MAPPER.readValue(labelsJson, new TypeReference<>() {});

      if (labelsList == null || labelsList.isEmpty()) {
        return "";
      }

      List<String> labelPairs = new ArrayList<>();
      for (Map<String, String> labelMap : labelsList) {
        // Each map should have exactly one key-value pair
        for (Map.Entry<String, String> entry : labelMap.entrySet()) {
          String key = entry.getKey();
          String value = entry.getValue();
          if (StringUtils.isNotBlank(key) && value != null) {
            labelPairs.add(String.format("(\"%s\",\"%s\")", key, value));
          }
        }
      }

      if (labelPairs.isEmpty()) {
        return "";
      }

      return "[" + String.join(",", labelPairs) + "]";

    } catch (Exception e) {
      LOG.warn(
          "Failed to parse labels JSON using Jackson: {}. Error: {}. Using as-is.",
          labelsJson,
          e.getMessage());
      return labelsJson; // Return as-is if parsing fails
    }
  }

  /**
   * Converts JSON array format tags to BigQuery SQL format.
   *
   * <p>Similar to labels conversion but for IAM tags. Tags use the same format as labels in
   * BigQuery SQL.
   *
   * @param tagsJson JSON array string
   * @return BigQuery SQL format tags string
   */
  private String convertTagsToSqlFormat(String tagsJson) {
    // Tags use the same format as labels in BigQuery SQL
    return convertLabelsToSqlFormat(tagsJson);
  }

  @Override
  public void create(String databaseName, String comment, Map<String, String> properties)
      throws SchemaAlreadyExistsException {
    // Validate properties before creation
    validateProperties(properties, false);

    LOG.info("Beginning to create database {}", databaseName);
    String originComment = StringIdentifier.removeIdFromComment(comment);
    // Check if schema comment is supported by the database
    // For BigQuery, supportSchemaComment() returns true, so this check will pass
    // This is a standard pattern from JdbcDatabaseOperations to ensure compatibility
    if (!supportSchemaComment() && StringUtils.isNotEmpty(originComment)) {
      throw new UnsupportedOperationException(
          "Doesn't support setting schema comment: " + originComment);
    }

    try (final Connection connection = getConnection()) {
      JdbcConnectorUtils.executeUpdate(
          connection, generateCreateDatabaseSql(databaseName, comment, properties));
      LOG.info("Finished creating database {}", databaseName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Alters a dataset's properties using BigQuery ALTER SCHEMA statement.
   *
   * <p>Only mutable properties can be altered. Immutable properties (location, default_collation)
   * will be ignored with a warning.
   *
   * @param databaseName the dataset name to alter
   * @param properties the properties to update (only mutable properties will be applied)
   */
  @SuppressWarnings("unused")
  public void alter(String databaseName, Map<String, String> properties) {
    // Validate properties for ALTER operation
    validateProperties(properties, true);

    String alterSql = generateAlterDatabaseSql(databaseName, properties);
    if (StringUtils.isEmpty(alterSql)) {
      LOG.info("No mutable properties to alter for dataset '{}'", databaseName);
      return;
    }

    LOG.info("Beginning to alter database {}", databaseName);
    try (final Connection connection = getConnection()) {
      JdbcConnectorUtils.executeUpdate(connection, alterSql);
      LOG.info("Finished altering database {}", databaseName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Generates ALTER SCHEMA SET OPTIONS SQL for BigQuery.
   *
   * <p>BigQuery syntax: ALTER SCHEMA [IF EXISTS] dataset_name SET OPTIONS(schema_option_list)
   *
   * <p>Note: Only mutable properties can be altered. Immutable properties (location,
   * default_collation) cannot be changed after dataset creation.
   *
   * @param databaseName the dataset name
   * @param properties the properties to update (only mutable properties)
   * @return the ALTER SCHEMA SQL statement, or empty string if no mutable properties
   */
  protected String generateAlterDatabaseSql(String databaseName, Map<String, String> properties) {

    if (properties == null || properties.isEmpty()) {
      return "";
    }

    List<String> options = new ArrayList<>();

    // Define mutable properties with their SQL format
    // Format: property key -> SQL option format (with %s placeholder for value)
    Map<String, PropertyFormatter> mutableProperties = new HashMap<>();

    // Numeric properties (no quotes)
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.DEFAULT_TABLE_EXPIRATION_DAYS,
        new PropertyFormatter("default_table_expiration_days=%s", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.DEFAULT_PARTITION_EXPIRATION_DAYS,
        new PropertyFormatter("default_partition_expiration_days=%s", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.IS_CASE_INSENSITIVE,
        new PropertyFormatter("is_case_insensitive=%s", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.MAX_TIME_TRAVEL_HOURS,
        new PropertyFormatter("max_time_travel_hours=%s", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.IS_PRIMARY, new PropertyFormatter("is_primary=%s", false));

    // String properties (with quotes)
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.STORAGE_BILLING_MODEL,
        new PropertyFormatter("storage_billing_model=\"%s\"", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.DEFAULT_KMS_KEY_NAME,
        new PropertyFormatter("default_kms_key_name=\"%s\"", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.DEFAULT_ROUNDING_MODE,
        new PropertyFormatter("default_rounding_mode=\"%s\"", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.FAILOVER_RESERVATION,
        new PropertyFormatter("failover_reservation=\"%s\"", false));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.PRIMARY_REPLICA,
        new PropertyFormatter("primary_replica=\"%s\"", false));

    // String properties that need escaping
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.FRIENDLY_NAME,
        new PropertyFormatter("friendly_name=\"%s\"", true));
    mutableProperties.put(
        BigQuerySchemaPropertiesMetadata.DESCRIPTION,
        new PropertyFormatter("description=\"%s\"", true));

    // Process standard properties
    for (Map.Entry<String, PropertyFormatter> entry : mutableProperties.entrySet()) {
      String propertyKey = entry.getKey();
      PropertyFormatter formatter = entry.getValue();

      if (properties.containsKey(propertyKey)) {
        String value = properties.get(propertyKey);
        if (StringUtils.isNotEmpty(value)) {
          String formattedValue =
              formatter.needsEscaping ? BigQueryOperationUtils.escapeString(value) : value;
          options.add(String.format(formatter.format, formattedValue));
        }
      }
    }

    // Labels (mutable)
    if (properties.containsKey(BigQuerySchemaPropertiesMetadata.LABELS)) {
      String labels = properties.get(BigQuerySchemaPropertiesMetadata.LABELS);
      if (StringUtils.isNotEmpty(labels)) {
        String labelsClause = convertLabelsToSqlFormat(labels);
        if (StringUtils.isNotEmpty(labelsClause)) {
          options.add(String.format("labels=%s", labelsClause));
        }
      }
    }

    // IAM Tags (mutable)
    if (properties.containsKey(BigQuerySchemaPropertiesMetadata.TAGS)) {
      String tags = properties.get(BigQuerySchemaPropertiesMetadata.TAGS);
      if (StringUtils.isNotEmpty(tags)) {
        String tagsClause = convertTagsToSqlFormat(tags);
        if (StringUtils.isNotEmpty(tagsClause)) {
          options.add(String.format("tags=%s", tagsClause));
        }
      }
    }

    if (options.isEmpty()) {
      LOG.debug("No mutable properties to alter for dataset '{}'", databaseName);
      return "";
    }

    String result =
        String.format(
            "ALTER SCHEMA `%s`\nSET OPTIONS(\n  %s\n);",
            databaseName, String.join(",\n  ", options));

    LOG.info("Generated ALTER SCHEMA SQL for dataset '{}': {}", databaseName, result);
    return result;
  }

  /**
   * Validates dataset properties and logs warnings for unsupported properties.
   *
   * @param properties the properties to validate
   * @param isAlter true if this is for ALTER operation, false for CREATE
   */
  private void validateProperties(Map<String, String> properties, boolean isAlter) {
    if (properties == null || properties.isEmpty()) {
      return;
    }

    for (String propertyName : properties.keySet()) {
      // Skip Gravitino internal properties
      if (propertyName.equals(StringIdentifier.ID_KEY)) {
        continue;
      }

      // Check if property is supported
      if (!BigQuerySchemaPropertiesMetadata.isSupportedProperty(propertyName)) {
        LOG.warn(
            "Unknown property '{}' for BigQuery dataset. This property will be ignored. "
                + "Supported properties are: location, default_table_expiration_days, "
                + "default_partition_expiration_days, storage_billing_model, max_time_travel_hours, "
                + "default_kms_key_name, default_rounding_mode, is_case_insensitive, description, "
                + "labels, default_collation, friendly_name, failover_reservation, is_primary, "
                + "primary_replica, tags",
            propertyName);
      } else if (isAlter) {
        // For ALTER operations, check if property is mutable
        if (!BigQuerySchemaPropertiesMetadata.isMutableProperty(propertyName)) {
          LOG.warn(
              "Property '{}' is immutable and cannot be changed after dataset creation. "
                  + "This property will be ignored in ALTER operation.",
              propertyName);
        }
      }
    }
  }

  /**
   * Helper class to format property values for BigQuery SQL.
   *
   * <p>Encapsulates the SQL format string and whether the value needs escaping.
   */
  private static class PropertyFormatter {
    final String format;
    final boolean needsEscaping;

    PropertyFormatter(String format, boolean needsEscaping) {
      this.format = format;
      this.needsEscaping = needsEscaping;
    }
  }
}
