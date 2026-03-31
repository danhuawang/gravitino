/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.catalog.bigquery.operation;

import com.datastrato.gravitino.catalog.bigquery.BigQueryClientPool;
import com.datastrato.gravitino.catalog.bigquery.BigQueryTablePropertiesMetadata;
import com.datastrato.gravitino.catalog.bigquery.utils.BigQueryUtils;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableList;
import com.google.api.services.bigquery.model.TimePartitioning;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;

/**
 * Table operations for BigQuery.
 *
 * <p>Implements BigQuery-specific DDL operations for tables, including CREATE TABLE with
 * partitioning and clustering, and ALTER TABLE with BigQuery's specific limitations.
 *
 * <p>This class uses BigQuery API for cross-region table listing to avoid JDBC connection
 * limitations with region-specific datasets.
 */
public class BigQueryTableOperations extends JdbcTableOperations {

  private static final String BACKTICK = "`";

  private BigQueryClientPool clientPool;

  /**
   * Sets the BigQuery API client pool.
   *
   * @param clientPool BigQuery client pool for API operations
   */
  public void setClientPool(BigQueryClientPool clientPool) {
    this.clientPool = clientPool;
  }

  @Override
  protected Connection getConnection(String databaseName) throws SQLException {
    // In BigQuery, databaseName is actually the dataset name (schema)
    // We need to get a connection and set the schema, not the catalog
    Connection connection = dataSource.getConnection();

    LOG.debug("BigQuery connection established for dataset: {}", databaseName);

    // Set schema to the dataset name
    connection.setSchema(databaseName);

    return connection;
  }

  /**
   * Checks if a table exists using BigQuery API.
   *
   * <p>This method uses BigQuery API instead of JDBC for better performance and cross-region
   * support.
   *
   * @param databaseName the dataset name
   * @param tableName the table name
   * @return true if the table exists, false otherwise
   * @throws RuntimeException if API call fails for reasons other than table not found
   */
  @SuppressWarnings("unused")
  protected boolean tableExistsViaApi(String databaseName, String tableName) {
    if (clientPool == null) {
      return false;
    }

    try {
      Bigquery bigquery = clientPool.getClient();
      String projectId = clientPool.getProjectId();

      // Try to get the table
      bigquery.tables().get(projectId, databaseName, tableName).execute();
      return true;
    } catch (IOException e) {
      if (e.getMessage() != null && e.getMessage().contains("404")) {
        return false; // Table not found
      }
      // For other errors (permission denied, network issues, etc.), throw exception
      LOG.error(
          "Failed to check table existence via API for {}.{}: {}",
          databaseName,
          tableName,
          e.getMessage());
      throw new RuntimeException(
          String.format(
              "Failed to check table existence for %s.%s: %s",
              databaseName, tableName, e.getMessage()),
          e);
    }
  }

  /**
   * Lists all tables in the specified dataset using BigQuery API.
   *
   * <p>This method uses the BigQuery API instead of JDBC metadata to avoid regional limitations.
   * JDBC connections are region-specific and cannot access datasets in different regions, which
   * would cause "Database not found" errors.
   *
   * @param databaseName the dataset name
   * @return list of table names
   * @throws NoSuchSchemaException if the dataset does not exist
   */
  @Override
  public List<String> listTables(String databaseName) throws NoSuchSchemaException {
    final List<String> names = new ArrayList<>();

    // Use BigQuery API if client pool is available
    if (clientPool != null) {
      try {
        Bigquery bigquery = clientPool.getClient();
        String projectId = clientPool.getProjectId();

        LOG.debug("Listing tables using BigQuery API for dataset: {}", databaseName);

        // List all tables in the dataset using BigQuery API
        Bigquery.Tables.List request = bigquery.tables().list(projectId, databaseName);

        TableList response = request.execute();

        if (response.getTables() != null) {
          for (TableList.Tables table : response.getTables()) {
            String tableId = table.getTableReference().getTableId();
            names.add(tableId);
            LOG.debug("Found table: {} in dataset: {}", tableId, databaseName);
          }
        }

        LOG.info(
            "Listed {} tables using API for dataset: {} in project: {}",
            names.size(),
            databaseName,
            projectId);
        return names;

      } catch (IOException e) {
        // Check if it's a "not found" error
        if (e.getMessage() != null && e.getMessage().contains("404")) {
          LOG.warn("Dataset not found: {}", databaseName);
          throw new NoSuchSchemaException(
              "Dataset '%s' does not exist in project '%s'",
              databaseName, clientPool.getProjectId());
        }
        LOG.error("Failed to list tables using API for dataset: {}", databaseName, e);
        throw new RuntimeException(
            "Failed to list tables using BigQuery API for dataset: " + databaseName, e);
      }
    }

    // Fallback to JDBC if API client is not available
    LOG.warn(
        "BigQuery API client not available, falling back to JDBC for dataset: {} "
            + "(may fail for cross-region datasets)",
        databaseName);

    try (Connection connection = getConnection(databaseName);
        ResultSet tables = getTables(connection)) {
      while (tables.next()) {
        String tableSchem = tables.getString("TABLE_SCHEM");
        String tableName = tables.getString("TABLE_NAME");

        // In BigQuery, TABLE_SCHEM contains the dataset name, not TABLE_CAT
        if (Objects.equals(tableSchem, databaseName)) {
          names.add(tableName);
          LOG.debug("Found table: {} in dataset: {}", tableName, databaseName);
        }
      }
      LOG.info("Finished listing tables size {} for dataset {} ", names.size(), databaseName);
      return names;
    } catch (final SQLException se) {
      LOG.error("Failed to list tables for dataset: {}", databaseName, se);
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public void create(
      String databaseName,
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes)
      throws TableAlreadyExistsException {

    // In BigQuery, table name must be fully qualified with dataset
    String qualifiedTableName = databaseName + "." + tableName;

    try (Connection connection = getConnection(databaseName)) {

      String createTableSql =
          generateCreateTableSql(
              qualifiedTableName,
              columns,
              comment,
              properties,
              partitioning,
              distribution,
              indexes);

      JdbcConnectorUtils.executeUpdate(connection, createTableSql);

    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generateCreateTableSql(
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {

    // BigQuery does not support traditional indexes or constraints
    if (ArrayUtils.isNotEmpty(indexes)) {
      throw new UnsupportedOperationException(
          "BigQuery does not support indexes or constraints. "
              + "Use clustering instead for query optimization.");
    }

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("CREATE TABLE ");

    // In BigQuery, table name must be fully qualified with dataset
    // Since we don't have databaseName in this method, we need to override the create method
    // instead
    // For now, assume tableName might already be qualified, or we'll fix this in the create method
    if (!tableName.contains(".")) {
      // This shouldn't happen in BigQuery, but let's handle it gracefully
      LOG.warn("Table name {} is not fully qualified for BigQuery", tableName);
    }

    // Table name with backticks
    sqlBuilder.append(BACKTICK).append(tableName).append(BACKTICK);
    sqlBuilder.append(" (\n");

    // Add column definitions
    for (int i = 0; i < columns.length; i++) {
      JdbcColumn column = columns[i];
      sqlBuilder.append("  ").append(BACKTICK).append(column.name()).append(BACKTICK).append(" ");

      // Data type
      sqlBuilder.append(typeConverter.fromGravitino(column.dataType()));

      // NOT NULL constraint
      if (!column.nullable()) {
        sqlBuilder.append(" NOT NULL");
      }

      // Column description via OPTIONS
      if (StringUtils.isNotEmpty(column.comment())) {
        sqlBuilder
            .append(" OPTIONS(description=\"")
            .append(escapeString(column.comment()))
            .append("\")");
      }

      if (i < columns.length - 1) {
        sqlBuilder.append(",\n");
      }
    }

    sqlBuilder.append("\n)");

    // Add PARTITION BY clause
    if (ArrayUtils.isNotEmpty(partitioning)) {
      sqlBuilder.append("\n").append(generatePartitionClause(partitioning, columns));
    }

    // Add CLUSTER BY clause (from properties, not distribution)
    if (properties != null
        && properties.containsKey(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS)) {
      String clusteringFields = properties.get(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS);
      BigQueryUtils.validateClusteringFields(clusteringFields);
      sqlBuilder.append("\n").append(generateClusterByClause(clusteringFields));
    }

    // Validate distribution is NONE (clustering should be via properties)
    if (!Distributions.NONE.equals(distribution)) {
      throw new UnsupportedOperationException(
          "BigQuery clustering should be specified via table property 'clustering_fields', "
              + "not through Distribution parameter.");
    }

    // Add OPTIONS clause
    String optionsClause = generateTableOptionsClause(comment, properties, partitioning);
    if (!optionsClause.isEmpty()) {
      sqlBuilder.append("\n").append(optionsClause);
    }

    String result = sqlBuilder.append(";").toString();
    LOG.info("Generated CREATE TABLE SQL for {}: {}", tableName, result);
    return result;
  }

  @Override
  protected void purgeTable(String databaseName, String tableName) {
    LOG.info("Attempting to purge table {} from dataset {}", tableName, databaseName);

    // In BigQuery, table name must be fully qualified with dataset
    String qualifiedTableName = databaseName + "." + tableName;

    try (Connection connection = getConnection(databaseName)) {
      String dropTableSql = generatePurgeTableSql(qualifiedTableName);
      JdbcConnectorUtils.executeUpdate(connection, dropTableSql);
      LOG.info("Purged table {} from dataset {}", tableName, databaseName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected void dropTable(String databaseName, String tableName) {
    LOG.info("Attempting to drop table {} from dataset {}", tableName, databaseName);

    // In BigQuery, table name must be fully qualified with dataset
    String qualifiedTableName = databaseName + "." + tableName;

    try (Connection connection = getConnection(databaseName)) {
      String dropTableSql = generateDropTableSql(qualifiedTableName);
      JdbcConnectorUtils.executeUpdate(connection, dropTableSql);
      LOG.info("Dropped table {} from dataset {}", tableName, databaseName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    // BigQuery DROP TABLE immediately deletes the table
    return "DROP TABLE " + BACKTICK + tableName + BACKTICK;
  }

  protected String generateDropTableSql(String tableName) {
    // In BigQuery, DROP TABLE is the same as purge
    return generatePurgeTableSql(tableName);
  }

  /**
   * Generates PARTITION BY clause for CREATE TABLE.
   *
   * <p>BigQuery supports partitioning by: - DATE columns (direct partitioning for identity,
   * DATE_TRUNC for month/year) - TIMESTAMP columns (using DATE() function for day partitioning or
   * TIMESTAMP_TRUNC for hour) - DATETIME columns (using DATE() function for day partitioning or
   * DATETIME_TRUNC) - INTEGER columns (using RANGE_BUCKET)
   *
   * @param partitioning the partitioning transforms
   * @param columns the table columns to determine column types
   * @return the PARTITION BY clause
   */
  private String generatePartitionClause(Transform[] partitioning, JdbcColumn[] columns) {
    if (partitioning.length > 1) {
      throw new UnsupportedOperationException(
          "BigQuery supports only single-column partitioning, but got " + partitioning.length);
    }

    Transform transform = partitioning[0];
    String partitionFieldName = null;

    // Get the partition field name
    if (transform instanceof Transforms.DayTransform) {
      partitionFieldName = ((Transforms.DayTransform) transform).fieldName()[0];
    } else if (transform instanceof Transforms.MonthTransform) {
      partitionFieldName = ((Transforms.MonthTransform) transform).fieldName()[0];
    } else if (transform instanceof Transforms.YearTransform) {
      partitionFieldName = ((Transforms.YearTransform) transform).fieldName()[0];
    } else if (transform instanceof Transforms.HourTransform) {
      partitionFieldName = ((Transforms.HourTransform) transform).fieldName()[0];
    } else if (transform instanceof Transforms.IdentityTransform) {
      partitionFieldName = ((Transforms.IdentityTransform) transform).fieldName()[0];
    } else {
      throw new UnsupportedOperationException(
          "Unsupported partition transform: " + transform.getClass().getSimpleName());
    }

    if (partitionFieldName == null) {
      throw new UnsupportedOperationException(
          "Unsupported partition transform: " + transform.getClass().getSimpleName());
    }

    // Find the column type
    JdbcColumn partitionColumn = null;
    for (JdbcColumn column : columns) {
      if (column.name().equals(partitionFieldName)) {
        partitionColumn = column;
        break;
      }
    }

    if (partitionColumn == null) {
      throw new IllegalArgumentException(
          "Partition field '" + partitionFieldName + "' not found in table columns");
    }

    String columnType = typeConverter.fromGravitino(partitionColumn.dataType()).toLowerCase();
    String fieldWithBackticks = BACKTICK + partitionFieldName + BACKTICK;

    if (transform instanceof Transforms.DayTransform) {
      // For day partitioning
      if (columnType.equals("date")) {
        // For DATE columns, use direct partitioning (not DATE() function)
        return String.format("PARTITION BY %s", fieldWithBackticks);
      } else if (columnType.equals("timestamp") || columnType.equals("datetime")) {
        // For TIMESTAMP/DATETIME columns, use DATE() function
        return String.format("PARTITION BY DATE(%s)", fieldWithBackticks);
      } else {
        throw new UnsupportedOperationException(
            "Day partitioning is only supported for DATE, TIMESTAMP, and DATETIME columns, but got: "
                + columnType);
      }

    } else if (transform instanceof Transforms.MonthTransform) {
      switch (columnType) {
        case "date":
          return String.format("PARTITION BY DATE_TRUNC(%s, MONTH)", fieldWithBackticks);
        case "timestamp":
          return String.format("PARTITION BY TIMESTAMP_TRUNC(%s, MONTH)", fieldWithBackticks);
        case "datetime":
          return String.format("PARTITION BY DATETIME_TRUNC(%s, MONTH)", fieldWithBackticks);
        default:
          throw new UnsupportedOperationException(
              "Month partitioning is only supported for DATE, TIMESTAMP, and DATETIME columns, but got: "
                  + columnType);
      }

    } else if (transform instanceof Transforms.YearTransform) {
      switch (columnType) {
        case "date":
          return String.format("PARTITION BY DATE_TRUNC(%s, YEAR)", fieldWithBackticks);
        case "timestamp":
          return String.format("PARTITION BY TIMESTAMP_TRUNC(%s, YEAR)", fieldWithBackticks);
        case "datetime":
          return String.format("PARTITION BY DATETIME_TRUNC(%s, YEAR)", fieldWithBackticks);
        default:
          throw new UnsupportedOperationException(
              "Year partitioning is only supported for DATE, TIMESTAMP, and DATETIME columns, but got: "
                  + columnType);
      }

    } else if (transform instanceof Transforms.HourTransform) {
      // For hour partitioning
      if (columnType.equals("timestamp")) {
        return String.format("PARTITION BY TIMESTAMP_TRUNC(%s, HOUR)", fieldWithBackticks);
      } else if (columnType.equals("datetime")) {
        return String.format("PARTITION BY DATETIME_TRUNC(%s, HOUR)", fieldWithBackticks);
      } else {
        throw new UnsupportedOperationException(
            "Hour partitioning is only supported for TIMESTAMP and DATETIME columns, but got: "
                + columnType);
      }

    } else if (transform instanceof Transforms.IdentityTransform) {
      switch (columnType) {
        case "date":
          // Direct partitioning for DATE columns
          return String.format("PARTITION BY %s", fieldWithBackticks);
        case "timestamp":
        case "datetime":
          // For TIMESTAMP/DATETIME columns, use DATE() function to extract date part
          return String.format("PARTITION BY DATE(%s)", fieldWithBackticks);
        case "int64":
          // For INTEGER columns, would need RANGE_BUCKET (not implemented yet)
          throw new UnsupportedOperationException(
              "Identity partitioning for INTEGER columns (RANGE_BUCKET) is not yet implemented");
        default:
          throw new UnsupportedOperationException(
              "Identity partitioning is only supported for DATE, TIMESTAMP, DATETIME, and INT64 columns, but got: "
                  + columnType);
      }

    } else {
      throw new UnsupportedOperationException(
          "Unsupported partition transform: " + transform.getClass().getSimpleName());
    }
  }

  /**
   * Generates CLUSTER BY clause.
   *
   * @param clusteringFields comma-separated field names
   * @return the CLUSTER BY clause
   */
  private String generateClusterByClause(String clusteringFields) {
    String[] fields = clusteringFields.split(",");
    String fieldList =
        Arrays.stream(fields)
            .map(String::trim)
            .map(field -> BACKTICK + field + BACKTICK)
            .collect(Collectors.joining(", "));
    return "CLUSTER BY " + fieldList;
  }

  private String generateTableOptionsClause(
      String comment, Map<String, String> properties, Transform[] partitioning) {
    List<String> options = new ArrayList<>();

    // Description
    if (StringUtils.isNotEmpty(comment)) {
      options.add(String.format("description=\"%s\"", escapeString(comment)));
    }

    if (properties != null) {
      // Partition expiration days - only valid for partitioned tables
      if (properties.containsKey(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS)) {
        if (ArrayUtils.isEmpty(partitioning)) {
          LOG.warn(
              "partition_expiration_days property is ignored because table is not partitioned. "
                  + "This property is only supported for tables with PARTITION BY clause.");
        } else {
          options.add(
              String.format(
                  "partition_expiration_days=%s",
                  properties.get(BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS)));
        }
      }

      // Require partition filter - only valid for partitioned tables
      if (properties.containsKey(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER)) {
        if (ArrayUtils.isEmpty(partitioning)) {
          LOG.warn(
              "require_partition_filter property is ignored because table is not partitioned. "
                  + "This property is only supported for tables with PARTITION BY clause.");
        } else {
          options.add(
              String.format(
                  "require_partition_filter=%s",
                  properties.get(BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER)));
        }
      }

      // Expiration timestamp
      if (properties.containsKey(BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP)) {
        options.add(
            String.format(
                "expiration_timestamp=TIMESTAMP \"%s\"",
                properties.get(BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP)));
      }

      // Friendly name
      if (properties.containsKey(BigQueryTablePropertiesMetadata.FRIENDLY_NAME)) {
        options.add(
            String.format(
                "friendly_name=\"%s\"",
                escapeString(properties.get(BigQueryTablePropertiesMetadata.FRIENDLY_NAME))));
      }

      // Labels - with proper formatting
      if (properties.containsKey(BigQueryTablePropertiesMetadata.LABELS)) {
        String labelsValue =
            formatLabelsValue(properties.get(BigQueryTablePropertiesMetadata.LABELS));
        if (!"[]".equals(labelsValue)) {
          options.add(String.format("labels=%s", labelsValue));
        }
      }

      // KMS key name for encryption
      if (properties.containsKey(BigQueryTablePropertiesMetadata.KMS_KEY_NAME)) {
        options.add(
            String.format(
                "kms_key_name=\"%s\"",
                escapeString(properties.get(BigQueryTablePropertiesMetadata.KMS_KEY_NAME))));
      }

      // Default rounding mode
      if (properties.containsKey(BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE)) {
        String roundingMode = properties.get(BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE);
        validateRoundingMode(roundingMode);
        options.add(String.format("default_rounding_mode=\"%s\"", roundingMode));
      }

      // Enable change history (Preview)
      if (properties.containsKey(BigQueryTablePropertiesMetadata.ENABLE_CHANGE_HISTORY)) {
        options.add(
            String.format(
                "enable_change_history=%s",
                properties.get(BigQueryTablePropertiesMetadata.ENABLE_CHANGE_HISTORY)));
      }

      // Max staleness
      if (properties.containsKey(BigQueryTablePropertiesMetadata.MAX_STALENESS)) {
        options.add(
            String.format(
                "max_staleness=%s", properties.get(BigQueryTablePropertiesMetadata.MAX_STALENESS)));
      }

      // Enable fine-grained mutations (Preview)
      if (properties.containsKey(BigQueryTablePropertiesMetadata.ENABLE_FINE_GRAINED_MUTATIONS)) {
        options.add(
            String.format(
                "enable_fine_grained_mutations=%s",
                properties.get(BigQueryTablePropertiesMetadata.ENABLE_FINE_GRAINED_MUTATIONS)));
      }

      // Managed table properties (Preview)
      if (properties.containsKey(BigQueryTablePropertiesMetadata.STORAGE_URI)) {
        options.add(
            String.format(
                "storage_uri=\"%s\"",
                escapeString(properties.get(BigQueryTablePropertiesMetadata.STORAGE_URI))));
      }

      if (properties.containsKey(BigQueryTablePropertiesMetadata.FILE_FORMAT)) {
        String fileFormat = properties.get(BigQueryTablePropertiesMetadata.FILE_FORMAT);
        validateFileFormat(fileFormat);
        options.add(String.format("file_format=\"%s\"", fileFormat));
      }

      if (properties.containsKey(BigQueryTablePropertiesMetadata.TABLE_FORMAT)) {
        String tableFormat = properties.get(BigQueryTablePropertiesMetadata.TABLE_FORMAT);
        validateTableFormat(tableFormat);
        options.add(String.format("table_format=\"%s\"", tableFormat));
      }

      // IAM tags
      if (properties.containsKey(BigQueryTablePropertiesMetadata.TAGS)) {
        String tagsValue = formatTagsValue(properties.get(BigQueryTablePropertiesMetadata.TAGS));
        if (!"[]".equals(tagsValue)) {
          options.add(String.format("tags=%s", tagsValue));
        }
      }
    }

    if (options.isEmpty()) {
      return "";
    }

    return "OPTIONS(\n  " + String.join(",\n  ", options) + "\n)";
  }

  /**
   * Escapes special characters in strings for SQL.
   *
   * @param str the string to escape
   * @return the escaped string
   */
  private String escapeString(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  @Override
  protected boolean getAutoIncrementInfo(ResultSet resultSet) {
    // BigQuery does not support auto increment
    return false;
  }

  @Override
  protected Map<String, String> getTableProperties(Connection connection, String tableName) {
    // Try to get properties via BigQuery API if available
    if (clientPool != null) {
      try {
        String databaseName = connection.getSchema();
        return getTablePropertiesViaApi(databaseName, tableName);
      } catch (Exception e) {
        LOG.warn(
            "Failed to get table properties via BigQuery API for table: {}, falling back to empty map",
            tableName,
            e);
      }
    }

    // Fallback: BigQuery table properties are not accessible via JDBC
    return Collections.emptyMap();
  }

  @Override
  protected List<Index> getIndexes(Connection connection, String databaseName, String tableName) {
    // BigQuery does not support traditional indexes
    return Collections.emptyList();
  }

  @Override
  protected Transform[] getTablePartitioning(
      Connection connection, String databaseName, String tableName) {
    // Try to get partitioning via BigQuery API if available
    if (clientPool != null) {
      try {
        return getTablePartitioningViaApi(databaseName, tableName);
      } catch (Exception e) {
        LOG.warn(
            "Failed to get table partitioning via BigQuery API for table: {}, falling back to empty array",
            tableName,
            e);
      }
    }

    // Fallback: BigQuery partitioning information is not accessible via JDBC
    return Transforms.EMPTY_TRANSFORM;
  }

  @Override
  protected void correctJdbcTableFields(
      Connection connection,
      String databaseName,
      String tableName,
      JdbcTable.Builder tableBuilder) {
    // Correct table comment via BigQuery API if available
    if (clientPool != null) {
      try {
        correctTableFieldsViaApi(databaseName, tableName, tableBuilder);
      } catch (Exception e) {
        LOG.warn(
            "Failed to correct table fields via BigQuery API for table: {}, using JDBC defaults",
            tableName,
            e);
      }
    }
  }

  @Override
  public void rename(String databaseName, String oldTableName, String newTableName)
      throws NoSuchTableException {
    LOG.info(
        "Attempting to rename table {}/{} to {}/{}",
        databaseName,
        oldTableName,
        databaseName,
        newTableName);

    // In BigQuery, table names must be fully qualified with dataset
    String qualifiedOldTableName = databaseName + "." + oldTableName;

    try (Connection connection = this.getConnection(databaseName)) {
      String sql =
          String.format("ALTER TABLE `%s` RENAME TO `%s`", qualifiedOldTableName, newTableName);
      JdbcConnectorUtils.executeUpdate(connection, sql);
      LOG.info(
          "Renamed table {}/{} to {}/{}", databaseName, oldTableName, databaseName, newTableName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    // This method is not used in BigQuery since we override the rename method
    // BigQuery supports ALTER TABLE RENAME TO since 2021
    // Syntax: ALTER TABLE [IF EXISTS] table_name RENAME TO new_table_name
    return String.format("ALTER TABLE `%s` RENAME TO `%s`", oldTableName, newTableName);
  }

  @Override
  protected String generateAlterTableSql(
      String databaseName, String tableName, TableChange... changes) {

    // In BigQuery, table name must be fully qualified with dataset
    String qualifiedTableName = databaseName + "." + tableName;

    List<String> alterClauses = new ArrayList<>();

    for (TableChange change : changes) {
      if (change instanceof TableChange.AddColumn) {
        TableChange.AddColumn addColumn = (TableChange.AddColumn) change;
        alterClauses.add(generateAddColumnClause(addColumn));
      } else if (change instanceof TableChange.DeleteColumn) {
        TableChange.DeleteColumn deleteColumn = (TableChange.DeleteColumn) change;
        alterClauses.add(generateDropColumnClause(deleteColumn));
      } else if (change instanceof TableChange.RenameColumn) {
        TableChange.RenameColumn renameColumn = (TableChange.RenameColumn) change;
        alterClauses.add(generateRenameColumnClause(renameColumn));
      } else if (change instanceof TableChange.UpdateColumnType) {
        TableChange.UpdateColumnType updateType = (TableChange.UpdateColumnType) change;
        alterClauses.add(generateAlterColumnTypeClause(updateType));
      } else if (change instanceof TableChange.UpdateColumnNullability) {
        TableChange.UpdateColumnNullability updateNullability =
            (TableChange.UpdateColumnNullability) change;
        alterClauses.add(generateAlterColumnNullabilityClause(updateNullability));
      } else if (change instanceof TableChange.SetProperty) {
        TableChange.SetProperty setProperty = (TableChange.SetProperty) change;
        alterClauses.add(generateSetOptionsClause(setProperty));
      } else if (change instanceof TableChange.UpdateComment) {
        TableChange.UpdateComment updateComment = (TableChange.UpdateComment) change;
        alterClauses.add(generateUpdateCommentClause(updateComment));
      } else {
        throw new UnsupportedOperationException(
            "Unsupported table change: " + change.getClass().getSimpleName());
      }
    }

    if (alterClauses.isEmpty()) {
      return "";
    }

    // BigQuery supports multiple ALTER operations in one statement
    String result =
        String.format(
            "ALTER TABLE `%s`\n%s;", qualifiedTableName, String.join(",\n", alterClauses));

    LOG.info("Generated ALTER TABLE SQL for {}: {}", qualifiedTableName, result);
    return result;
  }

  private String generateAddColumnClause(TableChange.AddColumn addColumn) {
    StringBuilder clause = new StringBuilder();
    clause.append("ADD COLUMN ");

    // BigQuery supports IF NOT EXISTS, but Gravitino's AddColumn doesn't have this flag
    // We'll add it as a future enhancement if needed

    String columnName = String.join(".", addColumn.getFieldName());
    clause.append(BACKTICK).append(columnName).append(BACKTICK).append(" ");

    // Data type
    clause.append(typeConverter.fromGravitino(addColumn.getDataType()));

    // NOT NULL constraint
    if (!addColumn.isNullable()) {
      clause.append(" NOT NULL");
    }

    // Column description
    if (StringUtils.isNotEmpty(addColumn.getComment())) {
      clause
          .append(" OPTIONS(description=\"")
          .append(escapeString(addColumn.getComment()))
          .append("\")");
    }

    return clause.toString();
  }

  private String generateDropColumnClause(TableChange.DeleteColumn deleteColumn) {
    StringBuilder clause = new StringBuilder();
    clause.append("DROP COLUMN ");

    if (deleteColumn.getIfExists() != null && deleteColumn.getIfExists()) {
      clause.append("IF EXISTS ");
    }

    String columnName = String.join(".", deleteColumn.fieldName());
    clause.append(BACKTICK).append(columnName).append(BACKTICK);

    return clause.toString();
  }

  private String generateRenameColumnClause(TableChange.RenameColumn renameColumn) {
    String oldName = String.join(".", renameColumn.fieldName());
    String newName = renameColumn.getNewName();

    return String.format("RENAME COLUMN `%s` TO `%s`", oldName, newName);
  }

  private String generateAlterColumnTypeClause(TableChange.UpdateColumnType updateType) {
    String columnName = String.join(".", updateType.fieldName());
    String newType = typeConverter.fromGravitino(updateType.getNewDataType());

    return String.format("ALTER COLUMN `%s` SET DATA TYPE %s", columnName, newType);
  }

  private String generateAlterColumnNullabilityClause(
      TableChange.UpdateColumnNullability updateNullability) {
    String columnName = String.join(".", updateNullability.fieldName());

    if (updateNullability.nullable()) {
      return String.format("ALTER COLUMN `%s` DROP NOT NULL", columnName);
    } else {
      throw new UnsupportedOperationException(
          "BigQuery does not support adding NOT NULL constraint to existing columns. "
              + "Only DROP NOT NULL is supported.");
    }
  }

  private String generateSetOptionsClause(TableChange.SetProperty setProperty) {
    String property = setProperty.getProperty();
    String value = setProperty.getValue();

    // Map Gravitino properties to BigQuery table options
    String optionName = mapPropertyToOption(property);
    String optionValue = formatOptionValue(property, value);

    return String.format("SET OPTIONS(%s=%s)", optionName, optionValue);
  }

  private String generateUpdateCommentClause(TableChange.UpdateComment updateComment) {
    String newComment = updateComment.getNewComment();
    if (newComment == null) {
      newComment = "";
    }
    // Escape single quotes in comment
    String escapedComment = newComment.replace("'", "\\'");
    return String.format("SET OPTIONS(description='%s')", escapedComment);
  }

  // Property mapping from Gravitino to BigQuery options
  private static final Map<String, String> PROPERTY_TO_OPTION_MAP =
      Map.of(
          "comment",
          "description",
          BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP,
          "expiration_timestamp",
          BigQueryTablePropertiesMetadata.FRIENDLY_NAME,
          "friendly_name",
          BigQueryTablePropertiesMetadata.LABELS,
          "labels",
          BigQueryTablePropertiesMetadata.KMS_KEY_NAME,
          "kms_key_name",
          BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE,
          "default_rounding_mode",
          BigQueryTablePropertiesMetadata.ENABLE_CHANGE_HISTORY,
          "enable_change_history",
          BigQueryTablePropertiesMetadata.MAX_STALENESS,
          "max_staleness",
          BigQueryTablePropertiesMetadata.ENABLE_FINE_GRAINED_MUTATIONS,
          "enable_fine_grained_mutations",
          BigQueryTablePropertiesMetadata.STORAGE_URI,
          "storage_uri");

  // Additional property mappings (Map.of has a limit of 10 entries)
  private static final Map<String, String> ADDITIONAL_PROPERTY_MAP =
      Map.of(
          BigQueryTablePropertiesMetadata.FILE_FORMAT, "file_format",
          BigQueryTablePropertiesMetadata.TABLE_FORMAT, "table_format",
          BigQueryTablePropertiesMetadata.TAGS, "tags");

  private String mapPropertyToOption(String property) {
    // Check main property map first
    String option = PROPERTY_TO_OPTION_MAP.get(property);
    if (option != null) {
      return option;
    }

    // Check additional property map
    option = ADDITIONAL_PROPERTY_MAP.get(property);
    if (option != null) {
      return option;
    }

    // Use as-is for BigQuery-specific properties
    return property;
  }

  private String formatOptionValue(String property, String value) {
    // Format value based on property type
    switch (property) {
      case "comment":
      case BigQueryTablePropertiesMetadata.FRIENDLY_NAME:
      case BigQueryTablePropertiesMetadata.KMS_KEY_NAME:
      case BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE:
      case BigQueryTablePropertiesMetadata.STORAGE_URI:
      case BigQueryTablePropertiesMetadata.FILE_FORMAT:
      case BigQueryTablePropertiesMetadata.TABLE_FORMAT:
        return "\"" + escapeString(value) + "\"";
      case BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP:
        return "TIMESTAMP \"" + value + "\"";
      case BigQueryTablePropertiesMetadata.LABELS:
        return formatLabelsValue(value);
      case BigQueryTablePropertiesMetadata.TAGS:
        return formatTagsValue(value);
      case BigQueryTablePropertiesMetadata.ENABLE_CHANGE_HISTORY:
      case BigQueryTablePropertiesMetadata.ENABLE_FINE_GRAINED_MUTATIONS:
        // Boolean values
        return value.toLowerCase();
      case BigQueryTablePropertiesMetadata.MAX_STALENESS:
        // Interval values don't need quotes
        return value;
      default:
        // Try to determine if it's a string or numeric value
        try {
          Double.parseDouble(value);
          return value; // Numeric value
        } catch (NumberFormatException e) {
          // Check if it's a boolean
          if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return value.toLowerCase();
          }
          // String value
          return "\"" + escapeString(value) + "\"";
        }
    }
  }

  private String formatLabelsValue(String labelsJson) {
    // Convert JSON array format to BigQuery labels format
    // Input: "[{\"key1\":\"value1\"},{\"key2\":\"value2\"}]"
    // Output: [("key1", "value1"), ("key2", "value2")]

    if (labelsJson == null || labelsJson.trim().isEmpty()) {
      return "[]";
    }

    LOG.info("Processing labels JSON: {}", labelsJson);

    try {
      // Remove outer brackets and whitespace
      String content = labelsJson.trim();
      if (content.startsWith("[") && content.endsWith("]")) {
        content = content.substring(1, content.length() - 1).trim();
      }

      if (content.isEmpty()) {
        return "[]";
      }

      // Handle multiple objects separated by commas
      // Split by },{ but be careful about commas inside strings
      List<String> labelPairs = new ArrayList<>();

      // Simple approach: split by },{
      String[] rawPairs = content.split("},\\s*\\{");

      for (String pair : rawPairs) {
        // Clean up the pair
        String cleanPair = pair.trim();
        if (cleanPair.startsWith("{")) {
          cleanPair = cleanPair.substring(1);
        }
        if (cleanPair.endsWith("}")) {
          cleanPair = cleanPair.substring(0, cleanPair.length() - 1);
        }

        // Parse key:value pairs
        // Look for pattern "key":"value"
        String[] keyValue = cleanPair.split("\"\\s*:\\s*\"");
        if (keyValue.length == 2) {
          String key = keyValue[0].replace("\"", "").trim();
          String value = keyValue[1].replace("\"", "").trim();

          if (!key.isEmpty() && !value.isEmpty()) {
            labelPairs.add(String.format("(\"%s\", \"%s\")", key, value));
          }
        }
      }

      if (labelPairs.isEmpty()) {
        return "[]";
      }

      String result = "[" + String.join(", ", labelPairs) + "]";
      LOG.info("Formatted labels: {}", result);
      return result;

    } catch (Exception e) {
      LOG.warn("Failed to parse labels JSON: {}, returning empty array", labelsJson, e);
      return "[]";
    }
  }

  /**
   * Formats tags value from JSON to BigQuery format.
   *
   * @param tagsJson JSON array of tag objects
   * @return BigQuery tags format
   */
  private String formatTagsValue(String tagsJson) {
    // Convert JSON array format to BigQuery tags format
    // Input: "[{\"key1\":\"value1\"},{\"key2\":\"value2\"}]"
    // Output: [("key1", "value1"), ("key2", "value2")]

    // Tags use the same format as labels
    return formatLabelsValue(tagsJson);
  }

  /**
   * Validates rounding mode value.
   *
   * @param roundingMode the rounding mode to validate
   * @throws IllegalArgumentException if invalid rounding mode
   */
  private void validateRoundingMode(String roundingMode) {
    if (StringUtils.isBlank(roundingMode)) {
      return;
    }

    String mode = roundingMode.trim().toUpperCase();
    if (!"ROUND_HALF_AWAY_FROM_ZERO".equals(mode) && !"ROUND_HALF_EVEN".equals(mode)) {
      throw new IllegalArgumentException(
          "Invalid rounding mode: "
              + roundingMode
              + ". "
              + "Supported values: ROUND_HALF_AWAY_FROM_ZERO, ROUND_HALF_EVEN");
    }
  }

  /**
   * Validates file format value.
   *
   * @param fileFormat the file format to validate
   * @throws IllegalArgumentException if invalid file format
   */
  private void validateFileFormat(String fileFormat) {
    if (StringUtils.isBlank(fileFormat)) {
      return;
    }

    String format = fileFormat.trim().toUpperCase();
    if (!"PARQUET".equals(format)) {
      throw new IllegalArgumentException(
          "Invalid file format: "
              + fileFormat
              + ". "
              + "Only PARQUET is supported for managed tables.");
    }
  }

  /**
   * Validates table format value.
   *
   * @param tableFormat the table format to validate
   * @throws IllegalArgumentException if invalid table format
   */
  private void validateTableFormat(String tableFormat) {
    if (StringUtils.isBlank(tableFormat)) {
      return;
    }

    String format = tableFormat.trim().toUpperCase();
    if (!"ICEBERG".equals(format)) {
      throw new IllegalArgumentException(
          "Invalid table format: "
              + tableFormat
              + ". "
              + "Only ICEBERG is supported for managed tables.");
    }
  }

  @Override
  protected Distribution getDistributionInfo(
      Connection connection, String databaseName, String tableName) {
    // BigQuery uses clustering instead of traditional distribution
    // Return NONE distribution as clustering is handled via table properties
    return Distributions.NONE;
  }

  /**
   * Gets table properties via BigQuery API.
   *
   * @param databaseName the dataset name
   * @param tableName the table name
   * @return map of table properties
   * @throws IOException if API call fails
   */
  private Map<String, String> getTablePropertiesViaApi(String databaseName, String tableName)
      throws IOException {
    Bigquery bigquery = clientPool.getClient();
    String projectId = clientPool.getProjectId();

    Table table = bigquery.tables().get(projectId, databaseName, tableName).execute();

    Map<String, String> properties = new HashMap<>();

    // Clustering fields
    if (table.getClustering() != null) {
      List<String> fields = table.getClustering().getFields();
      if (CollectionUtils.isNotEmpty(fields)) {
        properties.put(BigQueryTablePropertiesMetadata.CLUSTERING_FIELDS, String.join(",", fields));
      }
    }

    // Time partitioning properties
    if (table.getTimePartitioning() != null) {
      TimePartitioning timePartitioning = table.getTimePartitioning();

      // Partition expiration days
      if (timePartitioning.getExpirationMs() != null) {
        long expirationMs = timePartitioning.getExpirationMs();
        double expirationDays = expirationMs / (1000.0 * 60 * 60 * 24);
        properties.put(
            BigQueryTablePropertiesMetadata.PARTITION_EXPIRATION_DAYS,
            String.valueOf(expirationDays));
      }

      // Require partition filter
      if (timePartitioning.getRequirePartitionFilter() != null) {
        properties.put(
            BigQueryTablePropertiesMetadata.REQUIRE_PARTITION_FILTER,
            String.valueOf(timePartitioning.getRequirePartitionFilter()));
      }
    }

    // Expiration timestamp
    if (table.getExpirationTime() != null) {
      long expirationMs = table.getExpirationTime();
      java.time.Instant instant = java.time.Instant.ofEpochMilli(expirationMs);
      properties.put(
          BigQueryTablePropertiesMetadata.EXPIRATION_TIMESTAMP,
          instant.toString().replace("Z", "+00:00"));
    }

    // Friendly name
    if (StringUtils.isNotBlank(table.getFriendlyName())) {
      properties.put(BigQueryTablePropertiesMetadata.FRIENDLY_NAME, table.getFriendlyName());
    }

    // Labels
    if (MapUtils.isNotEmpty(table.getLabels())) {
      String labelsJson = formatLabelsFromApi(table.getLabels());
      properties.put(BigQueryTablePropertiesMetadata.LABELS, labelsJson);
    }

    // KMS key name
    if (table.getEncryptionConfiguration() != null) {
      String kmsKeyName = table.getEncryptionConfiguration().getKmsKeyName();
      if (StringUtils.isNotBlank(kmsKeyName)) {
        properties.put(BigQueryTablePropertiesMetadata.KMS_KEY_NAME, kmsKeyName);
      }
    }

    // Default rounding mode
    if (StringUtils.isNotBlank(table.getDefaultRoundingMode())) {
      properties.put(
          BigQueryTablePropertiesMetadata.DEFAULT_ROUNDING_MODE, table.getDefaultRoundingMode());
    }

    return properties;
  }

  /**
   * Gets table partitioning via BigQuery API.
   *
   * @param databaseName the dataset name
   * @param tableName the table name
   * @return array of partition transforms
   * @throws IOException if API call fails
   */
  private Transform[] getTablePartitioningViaApi(String databaseName, String tableName)
      throws IOException {
    Bigquery bigquery = clientPool.getClient();
    String projectId = clientPool.getProjectId();

    Table table = bigquery.tables().get(projectId, databaseName, tableName).execute();

    if (table.getTimePartitioning() == null) {
      return Transforms.EMPTY_TRANSFORM;
    }

    TimePartitioning timePartitioning = table.getTimePartitioning();
    String type = timePartitioning.getType();
    String field = timePartitioning.getField();

    if (field == null) {
      // Ingestion-time partitioning (_PARTITIONTIME)
      return Transforms.EMPTY_TRANSFORM;
    }

    // Map BigQuery partition type to Gravitino transform
    switch (type) {
      case "DAY":
        return new Transform[] {Transforms.day(field)};
      case "HOUR":
        return new Transform[] {Transforms.hour(field)};
      case "MONTH":
        return new Transform[] {Transforms.month(field)};
      case "YEAR":
        return new Transform[] {Transforms.year(field)};
      default:
        throw new IllegalArgumentException(
            String.format(
                "Unsupported BigQuery partition type: %s. Gravitino only supports DAY, HOUR, MONTH, and YEAR partitioning.",
                type));
    }
  }

  /**
   * Corrects table fields via BigQuery API.
   *
   * @param databaseName the dataset name
   * @param tableName the table name
   * @param tableBuilder the table builder to update
   * @throws IOException if API call fails
   */
  private void correctTableFieldsViaApi(
      String databaseName, String tableName, JdbcTable.Builder tableBuilder) throws IOException {
    Bigquery bigquery = clientPool.getClient();
    String projectId = clientPool.getProjectId();

    Table table = bigquery.tables().get(projectId, databaseName, tableName).execute();

    // Correct table comment/description
    if (StringUtils.isNotBlank(table.getDescription())) {
      tableBuilder.withComment(table.getDescription());
    }
  }

  /**
   * Formats labels from BigQuery API response to JSON array format.
   *
   * @param labels map of labels from BigQuery API
   * @return JSON array string
   */
  private String formatLabelsFromApi(Map<String, String> labels) {
    if (MapUtils.isEmpty(labels)) {
      return "[]";
    }

    List<String> labelPairs = new ArrayList<>();
    for (Map.Entry<String, String> entry : labels.entrySet()) {
      labelPairs.add(String.format("{\"%s\":\"%s\"}", entry.getKey(), entry.getValue()));
    }

    return "[" + String.join(", ", labelPairs) + "]";
  }
}
