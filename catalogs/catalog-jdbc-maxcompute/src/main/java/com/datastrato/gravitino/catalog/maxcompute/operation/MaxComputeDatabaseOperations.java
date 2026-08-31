/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.operation;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.meta.AuditInfo;

/**
 * Database operations for MaxCompute.
 *
 * <p>MaxCompute has a three-layer structure: Project -> Schema -> Table. The Project is specified
 * in the JDBC URL (jdbc:odps:{endpoint}?project={project_name}), and Schema is the namespace within
 * a Project.
 *
 * <p>This implementation maps Gravitino Schema to MaxCompute Schema. Use "SHOW SCHEMAS" SQL command
 * to list schemas within the current project.
 *
 * <p>Schema operations require enabling schema mode in MaxCompute. This class automatically enables
 * schema mode by executing "set odps.namespace.schema=true" on each connection.
 */
public class MaxComputeDatabaseOperations extends JdbcDatabaseOperations {

  private static final String BACK_QUOTE = "`";

  /**
   * Enables schema mode on the given connection.
   *
   * <p>MaxCompute has a three-layer structure: Project -> Schema -> Table. Schema mode must be
   * enabled to use schema-level operations like CREATE SCHEMA, DROP SCHEMA, etc.
   *
   * @param connection the JDBC connection
   * @throws SQLException if the setting fails
   */
  private static void enableSchemaMode(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("set odps.namespace.schema=true");
    }
  }

  /**
   * Gets a connection with schema mode enabled.
   *
   * <p>MaxCompute requires schema mode to be enabled for schema operations. This method overrides
   * the base implementation to enable schema mode on each connection.
   *
   * @return a JDBC connection with schema mode enabled
   * @throws SQLException if connection fails or schema mode cannot be enabled
   */
  @Override
  protected Connection getConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    try {
      enableSchemaMode(connection);
      return connection;
    } catch (SQLException e) {
      try {
        connection.close();
      } catch (SQLException closeException) {
        // Log but don't mask the original exception
        LOG.warn("Failed to close connection after enableSchemaMode failure", closeException);
      }
      throw e;
    }
  }

  /**
   * Lists all schemas in the current MaxCompute project.
   *
   * <p>Uses "SHOW SCHEMAS" SQL command to get the list of schemas within the current project. Each
   * project has a default schema named "default" and can have additional custom schemas.
   *
   * <p>Note: DatabaseMetaData.getSchemas() returns Projects list, not Schemas within a Project, so
   * we use SQL command instead.
   *
   * <p>The SHOW SCHEMAS command may return results in different formats depending on the JDBC
   * driver version. This implementation handles both single-column and space-separated formats.
   *
   * @return a list of schema names
   */
  @Override
  public List<String> listDatabases() {
    List<String> schemaNames = new ArrayList<>();
    try (Connection connection = getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SHOW SCHEMAS")) {
      while (resultSet.next()) {
        String result = resultSet.getString(1);
        if (StringUtils.isNotBlank(result)) {
          // Handle both formats: one schema per row or space-separated schemas
          String[] schemas = result.trim().split("\\s+");
          for (String schemaName : schemas) {
            if (StringUtils.isNotBlank(schemaName) && !isSystemDatabase(schemaName)) {
              schemaNames.add(schemaName);
            }
          }
        }
      }
      return schemaNames;
    } catch (SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
  }

  /**
   * Loads a schema from MaxCompute.
   *
   * @param databaseName the name of the schema to load
   * @return the JdbcSchema representing the schema
   * @throws NoSuchSchemaException if the schema does not exist
   */
  @Override
  public JdbcSchema load(String databaseName) throws NoSuchSchemaException {
    List<String> allDatabases = listDatabases();
    String dbName =
        allDatabases.stream()
            .filter(db -> db.equalsIgnoreCase(databaseName))
            .findFirst()
            .orElseThrow(
                () -> new NoSuchSchemaException("Schema %s could not be found", databaseName));

    // MaxCompute does not support schema comments or properties via JDBC
    return JdbcSchema.builder()
        .withName(dbName)
        .withProperties(ImmutableMap.of())
        .withAuditInfo(AuditInfo.EMPTY)
        .build();
  }

  /**
   * Generates the SQL statement to create a schema in MaxCompute.
   *
   * <p>MaxCompute syntax: CREATE SCHEMA `schema_name`
   *
   * <p>Note: MaxCompute does not support schema comments or properties in CREATE SCHEMA statement.
   *
   * @param databaseName the name of the schema to create
   * @param comment the comment for the schema (not supported, will be ignored)
   * @param properties the properties for the schema (not supported)
   * @return the SQL statement to create the schema
   */
  @Override
  public String generateCreateDatabaseSql(
      String databaseName, String comment, Map<String, String> properties) {
    if (MapUtils.isNotEmpty(properties)) {
      throw new UnsupportedOperationException(
          "MaxCompute does not support setting schema properties.");
    }
    String sql = String.format("CREATE SCHEMA %s", quoteDatabaseName(databaseName));
    LOG.info("Generated create schema sql: {}", sql);
    return sql;
  }

  /**
   * Generates the SQL statement to drop a schema in MaxCompute.
   *
   * <p>MaxCompute syntax: DROP SCHEMA `schema_name`
   *
   * <p>Note: MaxCompute does not support CASCADE option in DROP SCHEMA. The schema must be empty
   * before dropping. When cascade is true, this method checks if the schema is empty and throws an
   * exception if it contains tables.
   *
   * @param databaseName the name of the schema to drop
   * @param cascade whether to check for non-empty schema (MaxCompute does not support CASCADE)
   * @return the SQL statement to drop the schema
   */
  @Override
  public String generateDropDatabaseSql(String databaseName, boolean cascade) {
    if (cascade) {
      // Check if schema has tables before dropping
      try (Connection connection = getConnection();
          Statement statement = connection.createStatement()) {
        String query = String.format("SHOW TABLES IN %s", quoteDatabaseName(databaseName));
        try (ResultSet resultSet = statement.executeQuery(query)) {
          if (resultSet.next()) {
            throw new IllegalStateException(
                String.format(
                    "Schema %s is not empty. MaxCompute does not support CASCADE drop. "
                        + "Please drop all tables in the schema first.",
                    databaseName));
          }
        }
      } catch (SQLException se) {
        throw this.exceptionMapper.toGravitinoException(se);
      }
    }
    String sql = String.format("DROP SCHEMA %s", quoteDatabaseName(databaseName));
    LOG.info("Generated drop schema sql: {}", sql);
    return sql;
  }

  @Override
  protected boolean supportSchemaComment() {
    return false;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return ImmutableSet.of("information_schema");
  }

  /**
   * Check whether it is a system database.
   *
   * @param dbName The name of the database.
   * @return whether it is a system database.
   */
  @Override
  protected boolean isSystemDatabase(String dbName) {
    if (dbName == null) {
      return false;
    }
    return createSysDatabaseNameSet().contains(dbName.toLowerCase(Locale.ROOT));
  }

  /**
   * Quotes a database/schema name for use in SQL statements.
   *
   * <p>Escapes backticks in the name by doubling them, then wraps the name in backticks.
   *
   * @param databaseName the database name to quote
   * @return the quoted database name
   */
  private String quoteDatabaseName(String databaseName) {
    // Escape backticks by doubling them
    String escaped = databaseName.replace(BACK_QUOTE, BACK_QUOTE + BACK_QUOTE);
    return BACK_QUOTE + escaped + BACK_QUOTE;
  }

  /**
   * Deletes a schema from MaxCompute.
   *
   * <p>This method first checks if the schema exists before attempting to drop it. If the schema
   * does not exist, it returns false instead of throwing an exception.
   *
   * @param databaseName the name of the schema to delete
   * @param cascade whether to cascade the delete (not supported by MaxCompute)
   * @return true if the schema was deleted, false if it did not exist
   */
  @Override
  public boolean delete(String databaseName, boolean cascade) {
    // First check if the schema exists
    List<String> allDatabases = listDatabases();
    boolean exists = allDatabases.stream().anyMatch(db -> db.equalsIgnoreCase(databaseName));
    if (!exists) {
      return false;
    }
    // Schema exists, proceed with deletion
    return super.delete(databaseName, cascade);
  }
}
