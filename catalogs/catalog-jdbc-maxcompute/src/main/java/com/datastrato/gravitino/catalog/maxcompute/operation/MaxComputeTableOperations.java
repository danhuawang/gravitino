/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.operation;

import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.ACID_DATA_RETAIN_HOURS;
import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.LIFECYCLE;
import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.MAX_ACID_DATA_RETAIN_HOURS;
import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.MAX_WRITE_BUCKET_NUM;
import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.MIN_ACID_DATA_RETAIN_HOURS;
import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.TRANSACTIONAL;
import static com.datastrato.gravitino.catalog.maxcompute.MaxComputeTablePropertiesMetadata.WRITE_BUCKET_NUM;
import static com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeUtils.escapeSql;
import static com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeUtils.quoteIdentifier;
import static com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeUtils.quoteTableName;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTablePartitionOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchColumnException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;

/**
 * Table operations for MaxCompute.
 *
 * <p>MaxCompute supports the following table types:
 *
 * <ul>
 *   <li>Internal tables: Standard MaxCompute tables
 *   <li>Partitioned tables: Tables with up to 6 partition levels
 *   <li>Clustered tables: Hash or Range clustered tables
 *   <li>Transactional tables: Delta Tables with ACID support
 *   <li>External tables: Tables stored in OSS
 * </ul>
 *
 * <p>MaxCompute DDL syntax reference:
 *
 * <ul>
 *   <li>CREATE TABLE: CREATE TABLE table_name (col_name data_type, ...) [PARTITIONED BY (...)]
 *       [LIFECYCLE days]
 *   <li>DROP TABLE: DROP TABLE [IF EXISTS] table_name
 *   <li>ALTER TABLE: ALTER TABLE table_name RENAME TO new_name | ADD COLUMNS (...) | etc.
 * </ul>
 */
public class MaxComputeTableOperations extends JdbcTableOperations {

  private static final String NEW_LINE = "\n";
  private static final String JDBC_URL_KEY = "jdbc-url";

  /** Static mapping from MaxCompute property keywords to Gravitino property keys. */
  private static final Map<String, String> PROPERTY_KEY_MAPPING =
      Map.of(
          "lifecycle", LIFECYCLE,
          "transactional", TRANSACTIONAL,
          "write.bucket.num", WRITE_BUCKET_NUM,
          "acid.data.retain.hours", ACID_DATA_RETAIN_HOURS);

  /** The project name extracted from JDBC URL during initialization. */
  private String projectName;

  @Override
  public void initialize(
      DataSource dataSource,
      JdbcExceptionConverter exceptionMapper,
      JdbcTypeConverter jdbcTypeConverter,
      JdbcColumnDefaultValueConverter jdbcColumnDefaultValueConverter,
      Map<String, String> conf) {
    super.initialize(
        dataSource, exceptionMapper, jdbcTypeConverter, jdbcColumnDefaultValueConverter, conf);
    // Extract project name from jdbc-url in config and validate
    String extractedProjectName = extractProjectNameFromConfig(conf);
    if (StringUtils.isBlank(extractedProjectName)) {
      throw new IllegalArgumentException(
          "Failed to extract project name from jdbc-url. "
              + "Please ensure jdbc-url contains 'project=<project_name>' parameter.");
    }
    this.projectName = extractedProjectName;
    LOG.info("MaxComputeTableOperations initialized with project: {}", projectName);
  }

  /**
   * Extracts the project name from the JDBC URL in the configuration.
   *
   * <p>Expected URL format: jdbc:odps:xxx?project=project_name or
   * jdbc:odps:xxx?project=project_name&other=value
   *
   * @param conf the configuration map
   * @return the project name, or null if not found
   */
  private String extractProjectNameFromConfig(Map<String, String> conf) {
    String jdbcUrl = conf.get(JDBC_URL_KEY);
    if (StringUtils.isBlank(jdbcUrl)) {
      return null;
    }
    LOG.info("Extracting project name from jdbc-url: {}", jdbcUrl);

    // Use regex to extract project name: project=([^&]+)
    // Pattern.CASE_INSENSITIVE allows matching project=, PROJECT=, Project=, etc.
    // This is intentional as URL parameter names are typically case-insensitive
    Pattern pattern = Pattern.compile("project=([^&]+)", Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(jdbcUrl);

    if (matcher.find()) {
      String projectName = matcher.group(1);
      return StringUtils.isNotBlank(projectName) ? projectName : null;
    }

    return null;
  }

  @Override
  public JdbcTablePartitionOperations createJdbcTablePartitionOperations(JdbcTable loadedTable) {
    return new MaxComputeTablePartitionOperations(
        dataSource, loadedTable, exceptionMapper, projectName);
  }

  /**
   * Loads a table from MaxCompute.
   *
   * <p>This method overrides the parent implementation to properly handle MaxCompute's three-layer
   * namespace (Project -> Schema -> Table). It ensures that all metadata queries use the correct
   * project and schema names.
   *
   * @param databaseName the name of the schema
   * @param tableName the name of the table
   * @return the loaded JdbcTable
   * @throws NoSuchTableException if the table does not exist
   */
  @Override
  public JdbcTable load(String databaseName, String tableName) throws NoSuchTableException {
    LOG.info("Loading table {}.{} from MaxCompute", databaseName, tableName);

    try (Connection connection = getConnection(databaseName)) {
      // 1. Get table information using project and schema
      ResultSet tables = getTable(connection, databaseName, tableName);
      JdbcTable.Builder jdbcTableBuilder = getTableBuilder(tables, databaseName, tableName);

      // 2. Get column information using project and schema
      List<JdbcColumn> jdbcColumns = new ArrayList<>();
      ResultSet columns = getColumns(connection, databaseName, tableName);
      while (columns.next()) {
        JdbcColumn.Builder columnBuilder = getColumnBuilder(columns, databaseName, tableName);
        if (columnBuilder != null) {
          boolean autoIncrement = getAutoIncrementInfo(columns);
          columnBuilder.withAutoIncrement(autoIncrement);
          jdbcColumns.add(columnBuilder.build());
        }
      }
      jdbcTableBuilder.withColumns(jdbcColumns.toArray(new JdbcColumn[0]));

      // 3. Get index information
      List<Index> indexes = getIndexes(connection, databaseName, tableName);
      jdbcTableBuilder.withIndexes(indexes.toArray(new Index[0]));

      // 4. Get partitioning
      Transform[] tablePartitioning = getTablePartitioning(connection, databaseName, tableName);
      jdbcTableBuilder.withPartitioning(tablePartitioning);

      // 5. Get distribution information
      Distribution distribution = getDistributionInfo(connection, databaseName, tableName);
      jdbcTableBuilder.withDistribution(distribution);

      // 6. Get table properties using qualified table name (schema.table)
      String qualifiedTableName = databaseName + "." + tableName;
      Map<String, String> tableProperties = getTableProperties(connection, qualifiedTableName);
      jdbcTableBuilder.withProperties(tableProperties);

      // 7. Correct table fields if needed
      correctJdbcTableFields(connection, databaseName, tableName, jdbcTableBuilder);

      LOG.info("Successfully loaded table {}.{}", databaseName, tableName);
      return jdbcTableBuilder.withTableOperation(this).build();
    } catch (SQLException e) {
      throw exceptionMapper.toGravitinoException(e);
    }
  }

  /**
   * Renames a table in MaxCompute.
   *
   * <p>MaxCompute requires three-layer namespace for the old table name and simple name for the new
   * table name. Syntax: ALTER TABLE project.schema.old_table RENAME TO new_table
   *
   * @param databaseName the name of the schema
   * @param oldTableName the current table name
   * @param newTableName the new table name
   * @throws NoSuchTableException if the table does not exist
   */
  @Override
  public void rename(String databaseName, String oldTableName, String newTableName)
      throws NoSuchTableException {
    LOG.info(
        "Attempting to rename table {}.{} to {}.{}",
        databaseName,
        oldTableName,
        databaseName,
        newTableName);

    // Build three-layer namespace: project.schema.table
    String threeLayerOldTableName = projectName + "." + databaseName + "." + oldTableName;

    try (Connection connection = getConnection(databaseName)) {
      String sql = generateRenameTableSql(threeLayerOldTableName, newTableName);
      LOG.info("Executing rename SQL: {}", sql);
      JdbcConnectorUtils.executeUpdate(connection, sql);
      LOG.info(
          "Renamed table {}.{} to {}.{}", databaseName, oldTableName, databaseName, newTableName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Lists all tables in the specified schema.
   *
   * <p>Uses JDBC DatabaseMetaData.getTables() API to list tables. In MaxCompute's three-layer
   * structure (Project -> Schema -> Table), we pass the project name as catalog and schema name as
   * schemaPattern.
   *
   * <p>Note: The JDBC URL must include odpsNamespaceSchema=true for schema mode to work properly.
   *
   * @param databaseName the name of the schema
   * @return a list of table names
   * @throws NoSuchSchemaException if the schema does not exist
   */
  @Override
  public List<String> listTables(String databaseName) throws NoSuchSchemaException {
    List<String> tableNames = new ArrayList<>();

    if (StringUtils.isBlank(projectName)) {
      throw exceptionMapper.toGravitinoException(
          new SQLException("Project name not configured. Check jdbc-url configuration."));
    }

    try (Connection connection = dataSource.getConnection()) {
      // Use DatabaseMetaData.getTables() API
      // In MaxCompute with odpsNamespaceSchema=true:
      // - catalog = project name
      // - schemaPattern = schema name
      DatabaseMetaData metaData = connection.getMetaData();

      LOG.info(
          "Listing tables using DatabaseMetaData.getTables(project={}, schema={})",
          projectName,
          databaseName);

      try (ResultSet resultSet =
          metaData.getTables(projectName, databaseName, "%", new String[] {"TABLE"})) {
        while (resultSet.next()) {
          String tableName = resultSet.getString("TABLE_NAME");
          LOG.debug("Found table: {}", tableName);
          if (StringUtils.isNotBlank(tableName)) {
            tableNames.add(tableName);
          }
        }
      }

      LOG.info("Listed {} tables in schema {}", tableNames.size(), databaseName);
      return tableNames;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Creates a table in MaxCompute.
   *
   * <p>MaxCompute requires fully qualified table names (schema.table) when schema mode is enabled.
   * The JDBC URL must include odpsNamespaceSchema=true for this to work properly.
   */
  @Override
  public void create(
      String databaseName,
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {
    LOG.info("Attempting to create table {} in schema {}", tableName, databaseName);
    // Use fully qualified table name: schema.table
    String qualifiedTableName = databaseName + "." + tableName;
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      String sql =
          generateCreateTableSql(
              qualifiedTableName,
              columns,
              comment,
              properties,
              partitioning,
              distribution,
              indexes);
      LOG.info("Executing create table SQL: {}", sql);
      statement.execute(sql);
      LOG.info("Created table {} in schema {}", tableName, databaseName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Generates the CREATE TABLE SQL statement for MaxCompute.
   *
   * @param tableName the name of the table (maybe qualified as schema. table)
   * @param columns the columns of the table
   * @param comment the table comment
   * @param properties the table properties
   * @param partitioning the partitioning transforms
   * @param distribution the distribution (not supported by MaxCompute standard tables)
   * @param indexes the indexes (limited support in MaxCompute)
   * @return the CREATE TABLE SQL statement
   */
  @Override
  protected String generateCreateTableSql(
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("CREATE TABLE ").append(quoteTableName(tableName));
    sqlBuilder.append(" (").append(NEW_LINE);

    // Separate partition columns from regular columns
    List<JdbcColumn> regularColumns = new ArrayList<>();
    List<JdbcColumn> partitionColumns = new ArrayList<>();

    Set<String> partitionFieldNames = getPartitionFieldNames(partitioning);

    for (JdbcColumn column : columns) {
      if (partitionFieldNames.contains(column.name())) {
        partitionColumns.add(column);
      } else {
        regularColumns.add(column);
      }
    }

    // Add regular columns
    sqlBuilder.append(
        regularColumns.stream()
            .map(this::buildColumnDefinition)
            .collect(Collectors.joining("," + NEW_LINE)));

    // Handle primary key for Delta Table
    if (isTransactionalTable(properties)) {
      appendPrimaryKeySql(indexes, sqlBuilder);
    }

    sqlBuilder.append(NEW_LINE).append(")");

    // Add table comment
    if (StringUtils.isNotBlank(comment)) {
      sqlBuilder.append("\nCOMMENT '%s'".formatted(escapeSql(comment)));
    }

    // Add partition clause
    if (!partitionColumns.isEmpty()) {
      appendPartitionSql(partitionColumns, sqlBuilder);
    }

    // Add table properties (TBLPROPERTIES for Delta Table)
    appendTablePropertiesSql(properties, sqlBuilder);

    // Add lifecycle
    appendLifecycleSql(properties, sqlBuilder);

    String sql = sqlBuilder.toString();
    LOG.info("Generated create table SQL for {}: {}", tableName, sql);
    return sql;
  }

  /**
   * Alters a table in MaxCompute.
   *
   * <p>MaxCompute JDBC driver does not support executing multiple SQL statements in a single call
   * (separated by semicolons) unless using script mode. To avoid this limitation, we override this
   * method to execute each ALTER statement separately.
   *
   * @param databaseName the name of the schema
   * @param tableName the name of the table
   * @param changes the table changes to apply
   * @throws NoSuchTableException if the table does not exist
   */
  @Override
  public void alterTable(String databaseName, String tableName, TableChange... changes)
      throws NoSuchTableException {
    LOG.info("Attempting to alter table {}.{}", databaseName, tableName);

    // MaxCompute requires three-layer namespace: project.schema.table
    String qualifiedTableName = projectName + "." + databaseName + "." + tableName;

    JdbcTable lazyLoadTable = null;
    List<String> alterSqlList = new ArrayList<>();

    for (TableChange change : changes) {
      if (change instanceof TableChange.RenameTable renameTable) {
        // Rename table: use three-layer namespace for old table, simple name for new table
        // MaxCompute syntax: ALTER TABLE project.schema.old_table RENAME TO new_table
        String renameSql = generateRenameTableSql(qualifiedTableName, renameTable.getNewName());
        alterSqlList.add(renameSql);
      } else if (change instanceof TableChange.UpdateComment updateComment) {
        String sql =
            String.format(
                "ALTER TABLE %s SET COMMENT '%s'",
                quoteTableName(qualifiedTableName), escapeSql(updateComment.getNewComment()));
        alterSqlList.add(sql);
      } else if (change instanceof TableChange.AddColumn addColumn) {
        String sql =
            String.format(
                "ALTER TABLE %s %s",
                quoteTableName(qualifiedTableName), generateAddColumnSql(addColumn));
        alterSqlList.add(sql);
      } else if (change instanceof TableChange.DeleteColumn deleteColumn) {
        lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        String deleteSql = generateDeleteColumnSql(deleteColumn, lazyLoadTable);
        if (StringUtils.isNotBlank(deleteSql)) {
          String sql =
              String.format("ALTER TABLE %s %s", quoteTableName(qualifiedTableName), deleteSql);
          alterSqlList.add(sql);
        }
      } else if (change instanceof TableChange.RenameColumn renameColumn) {
        String sql =
            String.format(
                "ALTER TABLE %s %s",
                quoteTableName(qualifiedTableName), generateRenameColumnSql(renameColumn));
        alterSqlList.add(sql);
      } else if (change instanceof TableChange.UpdateColumnType) {
        throw new UnsupportedOperationException(
            "MaxCompute does not support changing column types. "
                + "Please recreate the table with the new column type.");
      } else if (change instanceof TableChange.UpdateColumnComment updateColumnComment) {
        lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        String sql =
            String.format(
                "ALTER TABLE %s %s",
                quoteTableName(qualifiedTableName),
                generateUpdateColumnCommentSql(updateColumnComment, lazyLoadTable));
        alterSqlList.add(sql);
      } else if (change instanceof TableChange.UpdateColumnNullability updateNullability) {
        lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        String sql =
            String.format(
                "ALTER TABLE %s %s",
                quoteTableName(qualifiedTableName),
                generateUpdateColumnNullabilitySql(updateNullability, lazyLoadTable));
        alterSqlList.add(sql);
      } else if (change instanceof TableChange.SetProperty setProperty) {
        String sql =
            String.format(
                "ALTER TABLE %s %s",
                quoteTableName(qualifiedTableName), generateSetPropertySql(setProperty));
        alterSqlList.add(sql);
      } else if (change instanceof TableChange.RemoveProperty) {
        throw new UnsupportedOperationException(
            "MaxCompute does not support removing table properties");
      } else {
        throw new UnsupportedOperationException(
            "Unsupported table change type: " + change.getClass().getName());
      }
    }

    if (alterSqlList.isEmpty()) {
      LOG.info("No changes to alter table {}.{}", databaseName, tableName);
      return;
    }

    // Execute each ALTER statement separately to avoid MaxCompute JDBC driver limitations
    try (Connection connection = getConnection(databaseName)) {
      for (String sql : alterSqlList) {
        LOG.info("Executing ALTER SQL: {}", sql);
        JdbcConnectorUtils.executeUpdate(connection, sql);
      }
      LOG.info("Successfully altered table {}.{}", databaseName, tableName);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Generates the ALTER TABLE SQL statement for MaxCompute.
   *
   * <p>Note: This method is kept for compatibility but is not used by the overridden alterTable
   * method. MaxCompute requires executing each ALTER statement separately.
   *
   * @param databaseName the name of the database/schema
   * @param tableName the name of the table
   * @param changes the table changes to apply
   * @return the ALTER TABLE SQL statement
   */
  @Override
  protected String generateAlterTableSql(
      String databaseName, String tableName, TableChange... changes) {

    // MaxCompute requires three-layer namespace: project.schema.table
    String qualifiedTableName = projectName + "." + databaseName + "." + tableName;

    JdbcTable lazyLoadTable = null;
    List<String> alterSql = new ArrayList<>();

    for (TableChange change : changes) {
      if (change instanceof TableChange.RenameTable renameTable) {
        // Rename table: use three-layer namespace for old table, simple name for new table
        // MaxCompute syntax: ALTER TABLE project.schema.old_table RENAME TO new_table
        return generateRenameTableSql(qualifiedTableName, renameTable.getNewName());
      } else if (change instanceof TableChange.UpdateComment updateComment) {
        alterSql.add(String.format("SET COMMENT '%s'", escapeSql(updateComment.getNewComment())));
      } else if (change instanceof TableChange.AddColumn addColumn) {
        alterSql.add(generateAddColumnSql(addColumn));
      } else if (change instanceof TableChange.DeleteColumn deleteColumn) {
        lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        String deleteSql = generateDeleteColumnSql(deleteColumn, lazyLoadTable);
        if (StringUtils.isNotBlank(deleteSql)) {
          alterSql.add(deleteSql);
        }
      } else if (change instanceof TableChange.RenameColumn renameColumn) {
        alterSql.add(generateRenameColumnSql(renameColumn));
      } else if (change instanceof TableChange.UpdateColumnType) {
        throw new UnsupportedOperationException(
            "MaxCompute does not support changing column types. "
                + "Please recreate the table with the new column type.");
      } else if (change instanceof TableChange.UpdateColumnComment updateColumnComment) {
        lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        alterSql.add(generateUpdateColumnCommentSql(updateColumnComment, lazyLoadTable));
      } else if (change instanceof TableChange.UpdateColumnNullability updateNullability) {
        lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        alterSql.add(generateUpdateColumnNullabilitySql(updateNullability, lazyLoadTable));
      } else if (change instanceof TableChange.SetProperty setProperty) {
        alterSql.add(generateSetPropertySql(setProperty));
      } else if (change instanceof TableChange.RemoveProperty) {
        throw new UnsupportedOperationException(
            "MaxCompute does not support removing table properties");
      } else {
        throw new UnsupportedOperationException(
            "Unsupported table change type: " + change.getClass().getName());
      }
    }

    if (alterSql.isEmpty()) {
      return "";
    }

    // Note: This generates multiple statements separated by semicolons, but MaxCompute JDBC
    // driver does not support this unless using script mode. The overridden alterTable method
    // executes each statement separately instead.
    String result =
        alterSql.stream()
            .map(sql -> String.format("ALTER TABLE %s %s", quoteTableName(qualifiedTableName), sql))
            .collect(Collectors.joining(";\n"));

    LOG.info("Generated alter table SQL for {}.{}: {}", databaseName, tableName, result);
    return result;
  }

  /**
   * Generates the RENAME TABLE SQL statement for MaxCompute.
   *
   * <p>MaxCompute syntax: ALTER TABLE schema.old_table RENAME TO new_table The new table name
   * should be a simple name without schema prefix.
   *
   * @param oldTableName the current table name (qualified as schema. table)
   * @param newTableName the new table name (simple name without schema)
   * @return the RENAME TABLE SQL statement
   */
  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    // Old table name is qualified (schema.table), new table name is simple
    return "ALTER TABLE %s RENAME TO %s"
        .formatted(quoteTableName(oldTableName), quoteIdentifier(newTableName));
  }

  /**
   * Drops a table from MaxCompute.
   *
   * <p>MaxCompute requires fully qualified table names (schema.table) when schema mode is enabled.
   * The JDBC URL must include odpsNamespaceSchema=true for this to work properly.
   */
  @Override
  protected void dropTable(String databaseName, String tableName) {
    LOG.info("Attempting to delete table {} from schema {}", tableName, databaseName);
    // Use fully qualified table name: schema.table
    String qualifiedTableName = databaseName + "." + tableName;
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      String sql = generateDropTableSql(qualifiedTableName);
      LOG.info("Executing drop table SQL: {}", sql);
      statement.execute(sql);
      LOG.info("Deleted table {} from schema {}", tableName, databaseName);
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Generates the DROP TABLE SQL statement for MaxCompute.
   *
   * @param tableName the name of the table to drop (maybe qualified as schema. table)
   * @return the DROP TABLE SQL statement
   */
  @Override
  protected String generateDropTableSql(String tableName) {
    return String.format("DROP TABLE IF EXISTS %s", quoteTableName(tableName));
  }

  /**
   * Generates the PURGE TABLE SQL statement for MaxCompute.
   *
   * <p>MaxCompute does not have a separate PURGE command. DROP TABLE permanently deletes the table.
   *
   * @param tableName the name of the table to purge
   * @return the DROP TABLE SQL statement
   */
  @Override
  protected String generatePurgeTableSql(String tableName) {
    // MaxCompute DROP TABLE is permanent, no separate purge needed
    return generateDropTableSql(tableName);
  }

  @Override
  protected Map<String, String> getTableProperties(Connection connection, String tableName)
      throws SQLException {
    Map<String, String> properties = new HashMap<>();
    // tableName must be qualified (schema.table) because MaxCompute's DESC EXTENDED
    // requires the full path to identify the table correctly within the three-layer
    // namespace (project.schema.table). The project is already set in the JDBC URL.
    String descSql = String.format("DESC EXTENDED %s", quoteTableName(tableName));

    try (Statement statement = connection.createStatement()) {
      LOG.debug("Desc SQL: {}", descSql);
      statement.execute(descSql);

      try (ResultSet resultSet = statement.getResultSet()) {
        // Parse DESC EXTENDED output to extract properties
        // MaxCompute DESC EXTENDED returns rows with property information
        while (resultSet.next()) {
          String line = resultSet.getString(1);
          if (StringUtils.isBlank(line)) {
            continue;
          }

          String lowerLine = line.toLowerCase();
          // Check each property keyword and extract value
          for (Map.Entry<String, String> entry : PROPERTY_KEY_MAPPING.entrySet()) {
            String keyword = entry.getKey();
            String propertyKey = entry.getValue();
            if (lowerLine.contains(keyword)) {
              String value = extractPropertyValue(line, keyword);
              if (StringUtils.isNotBlank(value)) {
                properties.put(propertyKey, value);
              }
            }
          }
        }
      }
    } catch (SQLException e) {
      // Log warning instead of throwing exception to prevent catalog/metalake from becoming
      // inaccessible. If we throw an exception here, users may not be able to open the metalake
      // in the UI when a required property is missing or invalid.
      LOG.warn("Failed to get table properties for {}: {}", tableName, e.getMessage());
      // Return empty map on failure, don't throw exception
      return new HashMap<>();
    }

    return properties;
  }

  @Override
  protected Transform[] getTablePartitioning(
      Connection connection, String databaseName, String tableName) throws SQLException {
    // Get partition columns from JDBC metadata
    String qualifiedTableName = databaseName + "." + tableName;
    List<Transform> transforms = new ArrayList<>();

    try {
      // Parse partition info from SHOW CREATE TABLE
      String showCreateSql =
          String.format("SHOW CREATE TABLE %s", quoteTableName(qualifiedTableName));
      try (Statement statement = connection.createStatement()) {
        LOG.debug("Show create SQL: {}", showCreateSql);
        statement.execute(showCreateSql);

        try (ResultSet resultSet = statement.getResultSet()) {
          StringBuilder createTableSql = new StringBuilder();
          while (resultSet.next()) {
            createTableSql.append(resultSet.getString(1));
          }

          String sql = createTableSql.toString();
          if (StringUtils.isNotBlank(sql)) {
            transforms = extractPartitioningFromCreateTable(sql);
          }
        }
      }
    } catch (SQLException e) {
      LOG.warn(
          "Failed to get partitioning info for {}.{}: {}", databaseName, tableName, e.getMessage());
    }

    return transforms.isEmpty() ? Transforms.EMPTY_TRANSFORM : transforms.toArray(new Transform[0]);
  }

  /**
   * Extracts partitioning information from CREATE TABLE SQL.
   *
   * @param createTableSql the CREATE TABLE SQL statement
   * @return list of partition transforms
   */
  private List<Transform> extractPartitioningFromCreateTable(String createTableSql) {
    List<Transform> transforms = new ArrayList<>();

    // Pattern to match PARTITIONED BY clause
    // Example: PARTITIONED BY (pt STRING, dt STRING)
    Pattern pattern =
        Pattern.compile(
            "PARTITIONED\\s+BY\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    Matcher matcher = pattern.matcher(createTableSql);

    if (matcher.find()) {
      String partitionClause = matcher.group(1);
      // Parse partition columns: "pt STRING, dt STRING" or "pt STRING COMMENT 'xxx'"
      String[] partitionDefs = partitionClause.split(",");

      for (String partitionDef : partitionDefs) {
        String trimmed = partitionDef.trim();
        if (StringUtils.isBlank(trimmed)) {
          continue;
        }

        // Extract column name (first word, possibly with backticks)
        String[] parts = trimmed.split("\\s+");
        if (parts.length > 0) {
          String columnName = parts[0].replace("`", "").trim();
          if (StringUtils.isNotBlank(columnName)) {
            transforms.add(Transforms.identity(columnName));
          }
        }
      }
    }

    return transforms;
  }

  /**
   * Extracts a property value from a DESC output line.
   *
   * <p>MaxCompute DESC EXTENDED output format may vary depending on the JDBC driver version and
   * server configuration. The output can use different separators between property names and
   * values, so we try multiple formats to ensure compatibility.
   *
   * @param line the line from DESC output
   * @param propertyName the property name to extract
   * @return the property value, or null if not found
   */
  private String extractPropertyValue(String line, String propertyName) {
    // Try different formats that may appear in DESC EXTENDED output:
    // 1. "property_name: value" (colon separator)
    // 2. "property_name=value" (equals separator)
    // 3. "property_name\tvalue" (tab separator)
    String lowerLine = line.toLowerCase();
    String lowerProp = propertyName.toLowerCase();

    int propIndex = lowerLine.indexOf(lowerProp);
    if (propIndex == -1) {
      return null;
    }

    String afterProp = line.substring(propIndex + propertyName.length()).trim();

    // Remove leading separators
    if (afterProp.startsWith(":") || afterProp.startsWith("=")) {
      afterProp = afterProp.substring(1).trim();
    }

    // Take the first word/value
    String[] parts = afterProp.split("[\\s,;]+");
    if (parts.length > 0 && StringUtils.isNotBlank(parts[0])) {
      return parts[0].replace("\"", "").replace("'", "");
    }

    return null;
  }

  @Override
  protected void correctJdbcTableFields(
      Connection connection, String databaseName, String tableName, JdbcTable.Builder tableBuilder)
      throws SQLException {
    // Correct table comment if not retrieved from JDBC metadata
    if (StringUtils.isBlank(tableBuilder.comment())) {
      String comment = getTableCommentFromDesc();
      if (StringUtils.isNotBlank(comment)) {
        tableBuilder.withComment(comment);
      }
    }
  }

  @Override
  protected ResultSet getTable(Connection connection, String databaseName, String tableName)
      throws SQLException {
    // MaxCompute uses three-layer namespace: Project -> Schema -> Table
    // We need to pass the project name as catalog and schema name as schemaPattern
    final DatabaseMetaData metaData = connection.getMetaData();
    LOG.debug(
        "Getting table metadata: project={}, schema={}, table={}",
        projectName,
        databaseName,
        tableName);
    return metaData.getTables(projectName, databaseName, tableName, null);
  }

  @Override
  protected ResultSet getColumns(Connection connection, String databaseName, String tableName)
      throws SQLException {
    // MaxCompute uses three-layer namespace: Project -> Schema -> Table
    // We need to pass the project name as catalog and schema name as schemaPattern
    final DatabaseMetaData metaData = connection.getMetaData();
    LOG.debug(
        "Getting column metadata: project={}, schema={}, table={}",
        projectName,
        databaseName,
        tableName);
    return metaData.getColumns(projectName, databaseName, tableName, null);
  }

  @Override
  protected boolean getAutoIncrementInfo(ResultSet resultSet) throws SQLException {
    // MaxCompute does not support auto-increment columns
    return false;
  }

  // ==================== Private Helper Methods ====================

  private String buildColumnDefinition(JdbcColumn column) {
    StringBuilder sb = new StringBuilder();
    sb.append("  ")
        .append(quoteIdentifier(column.name()))
        .append(" ")
        .append(typeConverter.fromGravitino(column.dataType()));

    // Add NOT NULL constraint
    if (!column.nullable()) {
      sb.append(" NOT NULL");
    }

    // Add default value
    if (!DEFAULT_VALUE_NOT_SET.equals(column.defaultValue())) {
      sb.append(" DEFAULT ")
          .append(columnDefaultValueConverter.fromGravitino(column.defaultValue()));
    }

    // Add column comment
    if (StringUtils.isNotBlank(column.comment())) {
      sb.append(" COMMENT '").append(escapeSql(column.comment())).append("'");
    }

    return sb.toString();
  }

  private Set<String> getPartitionFieldNames(Transform[] partitioning) {
    if (ArrayUtils.isEmpty(partitioning)) {
      return Collections.emptySet();
    }

    return Arrays.stream(partitioning)
        .filter(t -> t instanceof Transforms.IdentityTransform)
        .map(t -> ((Transforms.IdentityTransform) t).fieldName()[0])
        .collect(Collectors.toSet());
  }

  private boolean isTransactionalTable(Map<String, String> properties) {
    if (properties == null) {
      return false;
    }
    return "true".equalsIgnoreCase(properties.get(TRANSACTIONAL));
  }

  private void appendPrimaryKeySql(Index[] indexes, StringBuilder sqlBuilder) {
    if (ArrayUtils.isEmpty(indexes)) {
      return;
    }

    for (Index index : indexes) {
      if (index.type() == Index.IndexType.PRIMARY_KEY) {
        String pkColumns =
            Arrays.stream(index.fieldNames())
                .map(names -> quoteIdentifier(names[0]))
                .collect(Collectors.joining(", "));
        sqlBuilder.append(",").append(NEW_LINE);
        sqlBuilder.append("  PRIMARY KEY (").append(pkColumns).append(")");
        break;
      }
    }
  }

  private void appendPartitionSql(List<JdbcColumn> partitionColumns, StringBuilder sqlBuilder) {
    sqlBuilder.append(NEW_LINE).append("PARTITIONED BY (");
    sqlBuilder.append(
        partitionColumns.stream()
            .map(
                col -> {
                  StringBuilder partSb = new StringBuilder();
                  partSb
                      .append(quoteIdentifier(col.name()))
                      .append(" ")
                      .append(typeConverter.fromGravitino(col.dataType()));
                  if (StringUtils.isNotBlank(col.comment())) {
                    partSb.append(" COMMENT '%s'".formatted(escapeSql(col.comment())));
                  }
                  return partSb.toString();
                })
            .collect(Collectors.joining(", ")));
    sqlBuilder.append(")");
  }

  private void appendTablePropertiesSql(Map<String, String> properties, StringBuilder sqlBuilder) {
    if (properties == null || properties.isEmpty()) {
      return;
    }

    List<String> tblProperties = new ArrayList<>();

    if (isTransactionalTable(properties)) {
      tblProperties.add("\"transactional\"=\"true\"");

      String bucketNum = properties.get(WRITE_BUCKET_NUM);
      if (StringUtils.isNotBlank(bucketNum)) {
        // Validate bucket number range
        try {
          int bucketNumValue = Integer.parseInt(bucketNum);
          if (bucketNumValue < 1 || bucketNumValue > MAX_WRITE_BUCKET_NUM) {
            throw new IllegalArgumentException(
                String.format(
                    "Invalid write.bucket.num: %d. Must be between 1 and %d.",
                    bucketNumValue, MAX_WRITE_BUCKET_NUM));
          }
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              String.format("Invalid write.bucket.num: %s. Must be a valid integer.", bucketNum));
        }
        tblProperties.add(String.format("\"write.bucket.num\"=\"%s\"", bucketNum));
      }

      String retainHours = properties.get(ACID_DATA_RETAIN_HOURS);
      if (StringUtils.isNotBlank(retainHours)) {
        // Validate retention hours range
        try {
          int retainHoursValue = Integer.parseInt(retainHours);
          if (retainHoursValue < MIN_ACID_DATA_RETAIN_HOURS
              || retainHoursValue > MAX_ACID_DATA_RETAIN_HOURS) {
            throw new IllegalArgumentException(
                String.format(
                    "Invalid acid.data.retain.hours: %d. Must be between %d and %d.",
                    retainHoursValue, MIN_ACID_DATA_RETAIN_HOURS, MAX_ACID_DATA_RETAIN_HOURS));
          }
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              String.format(
                  "Invalid acid.data.retain.hours: %s. Must be a valid integer.", retainHours));
        }
        tblProperties.add(String.format("\"acid.data.retain.hours\"=\"%s\"", retainHours));
      }
    }

    if (!tblProperties.isEmpty()) {
      sqlBuilder.append(NEW_LINE).append("TBLPROPERTIES (");
      sqlBuilder.append(String.join(", ", tblProperties));
      sqlBuilder.append(")");
    }
  }

  private void appendLifecycleSql(Map<String, String> properties, StringBuilder sqlBuilder) {
    if (properties == null) {
      return;
    }

    String lifecycle = properties.get(LIFECYCLE);
    if (StringUtils.isNotBlank(lifecycle)) {
      try {
        int days = Integer.parseInt(lifecycle);
        // MaxCompute lifecycle: -1 means no expiration, positive values are valid
        // MaxCompute documentation doesn't specify an upper limit, but we validate it's reasonable
        if (days != -1 && days <= 0) {
          throw new IllegalArgumentException(
              String.format(
                  "Invalid lifecycle: %d. Must be -1 (no expiration) or a positive integer.",
                  days));
        }
        if (days > 0) {
          sqlBuilder.append(NEW_LINE).append("LIFECYCLE ").append(days);
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            String.format("Invalid lifecycle: %s. Must be a valid integer.", lifecycle));
      }
    }
  }

  private String generateAddColumnSql(TableChange.AddColumn addColumn) {
    Preconditions.checkArgument(
        addColumn.fieldName().length == 1, "MaxCompute does not support nested column names");

    String colName = addColumn.fieldName()[0];
    String dataType = typeConverter.fromGravitino(addColumn.getDataType());

    StringBuilder sb = new StringBuilder();
    sb.append("ADD COLUMNS (").append(quoteIdentifier(colName)).append(" ").append(dataType);

    if (StringUtils.isNotBlank(addColumn.getComment())) {
      sb.append(" COMMENT '%s'".formatted(escapeSql(addColumn.getComment())));
    }

    sb.append(")");
    return sb.toString();
  }

  private String generateDeleteColumnSql(
      TableChange.DeleteColumn deleteColumn, JdbcTable jdbcTable) {
    Preconditions.checkArgument(
        deleteColumn.fieldName().length == 1, "MaxCompute does not support nested column names");

    String colName = deleteColumn.fieldName()[0];

    // Check if column exists
    try {
      getJdbcColumnFromTable(jdbcTable, colName);
    } catch (NoSuchColumnException e) {
      if (BooleanUtils.isTrue(deleteColumn.getIfExists())) {
        return "";
      }
      throw new IllegalArgumentException("Column does not exist: " + colName);
    }

    // MaxCompute does not support DROP COLUMN directly
    // This would need to be done via table recreation
    throw new UnsupportedOperationException(
        "MaxCompute does not support dropping columns directly. "
            + "Please recreate the table without the column.");
  }

  private String generateRenameColumnSql(TableChange.RenameColumn renameColumn) {
    Preconditions.checkArgument(
        renameColumn.fieldName().length == 1, "MaxCompute does not support nested column names");

    String oldName = renameColumn.fieldName()[0];
    String newName = renameColumn.getNewName();

    return "CHANGE COLUMN %s RENAME TO %s"
        .formatted(quoteIdentifier(oldName), quoteIdentifier(newName));
  }

  private String generateUpdateColumnCommentSql(
      TableChange.UpdateColumnComment updateComment, JdbcTable jdbcTable) {
    Preconditions.checkArgument(
        updateComment.fieldName().length == 1, "MaxCompute does not support nested column names");

    String colName = updateComment.fieldName()[0];
    JdbcColumn column = getJdbcColumnFromTable(jdbcTable, colName);
    String dataType = typeConverter.fromGravitino(column.dataType());

    return "CHANGE COLUMN %s %s %s COMMENT '%s'"
        .formatted(
            quoteIdentifier(colName),
            quoteIdentifier(colName),
            dataType,
            escapeSql(updateComment.getNewComment()));
  }

  private String generateUpdateColumnNullabilitySql(
      TableChange.UpdateColumnNullability updateNullability, JdbcTable jdbcTable) {
    Preconditions.checkArgument(
        updateNullability.fieldName().length == 1,
        "MaxCompute does not support nested column names");

    String colName = updateNullability.fieldName()[0];
    JdbcColumn column = getJdbcColumnFromTable(jdbcTable, colName);
    String dataType = typeConverter.fromGravitino(column.dataType());

    StringBuilder sb = new StringBuilder();
    sb.append("CHANGE COLUMN ")
        .append(quoteIdentifier(colName))
        .append(" ")
        .append(quoteIdentifier(colName))
        .append(" ")
        .append(dataType);

    if (!updateNullability.nullable()) {
      sb.append(" NOT NULL");
    }

    if (StringUtils.isNotBlank(column.comment())) {
      sb.append(" COMMENT '%s'".formatted(escapeSql(column.comment())));
    }

    return sb.toString();
  }

  private String generateSetPropertySql(TableChange.SetProperty setProperty) {
    String key = setProperty.getProperty();
    String value = setProperty.getValue();

    if (LIFECYCLE.equals(key)) {
      return String.format("SET LIFECYCLE %s", value);
    }

    // For other properties, use SET TBLPROPERTIES
    return String.format("SET TBLPROPERTIES (\"%s\"=\"%s\")", key, value);
  }

  private String getTableCommentFromDesc() {
    // MaxCompute table comments are retrieved via JDBC metadata
    // This method is kept for potential future use if JDBC metadata is insufficient
    // Currently always returns null as comments are handled by JDBC DatabaseMetaData
    return null;
  }

  /**
   * Gets a connection without setting catalog.
   *
   * <p>MaxCompute uses a three-layer namespace (Project -> Schema -> Table) where the project is
   * specified in the JDBC URL. The parent class's getConnection(String catalog) calls
   * connection.setCatalog(catalog), which would incorrectly set the schema name as the project
   * name.
   *
   * <p>This override simply returns a connection from the data source without modifying the
   * catalog. Schema mode should be enabled via odpsNamespaceSchema=true in the JDBC URL.
   *
   * @param schema the schema name (not used for connection setup in MaxCompute)
   * @return a JDBC connection
   * @throws SQLException if connection fails
   */
  @Override
  protected Connection getConnection(String schema) throws SQLException {
    Connection connection = dataSource.getConnection();
    LOG.debug("Connection established for schema: {}", schema);
    return connection;
  }
}
