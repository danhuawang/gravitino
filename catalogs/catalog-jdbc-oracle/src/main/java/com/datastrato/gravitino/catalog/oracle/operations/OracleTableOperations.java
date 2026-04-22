/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.COMPRESSION;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.PARTITIONED;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.ROW_MOVEMENT;
import static com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata.TABLESPACE;
import static org.apache.gravitino.catalog.jdbc.JdbcTablePropertiesMetadata.COMMENT_KEY;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;

/** Table operations for Oracle. */
public class OracleTableOperations extends JdbcTableOperations {

  private static final String DOUBLE_QUOTE = "\"";
  private static final String GET_TABLE_PROPERTIES_SQL =
      "SELECT t.TABLESPACE_NAME, t.PARTITIONED, t.ROW_MOVEMENT, t.COMPRESSION, c.COMMENTS "
          + "FROM ALL_TABLES t LEFT JOIN ALL_TAB_COMMENTS c "
          + "ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME "
          + "WHERE t.OWNER = ? AND t.TABLE_NAME = ?";
  private static final String GET_INDEXES_SQL =
      "SELECT ac.CONSTRAINT_NAME, ac.CONSTRAINT_TYPE, acc.COLUMN_NAME, acc.POSITION "
          + "FROM ALL_CONSTRAINTS ac JOIN ALL_CONS_COLUMNS acc "
          + "ON ac.OWNER = acc.OWNER AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME "
          + "WHERE ac.OWNER = ? AND ac.TABLE_NAME = ? "
          + "AND ac.CONSTRAINT_TYPE IN ('P', 'U') "
          + "ORDER BY ac.CONSTRAINT_NAME, acc.POSITION";

  @Override
  protected String generateCreateTableSql(
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {
    Preconditions.checkArgument(
        ArrayUtils.isEmpty(partitioning), "Oracle does not support table partitioning in #600.");
    Preconditions.checkArgument(
        distribution == null || Distributions.NONE.equals(distribution),
        "Oracle does not support table distribution in #600.");

    List<String> columnDefinitions = new ArrayList<>();
    for (JdbcColumn column : columns) {
      if (column.autoIncrement()) {
        throw new UnsupportedOperationException(
            "Oracle 11g does not support AUTO_INCREMENT column.");
      }

      StringBuilder columnSql = new StringBuilder();
      columnSql
          .append(quotedName(column.name()))
          .append(" ")
          .append(typeConverter.fromGravitino(column.dataType()));
      if (!column.nullable()) {
        columnSql.append(" NOT NULL");
      }
      if (!DEFAULT_VALUE_NOT_SET.equals(column.defaultValue())) {
        columnSql
            .append(" DEFAULT ")
            .append(columnDefaultValueConverter.fromGravitino(column.defaultValue()));
      }
      columnDefinitions.add(columnSql.toString());
    }

    columnDefinitions.addAll(buildConstraintDefinitions(indexes));

    // Quote the table name and column names to preserve case sensitivity, as Oracle treats unquoted
    // identifiers as uppercase by default. This allows users to create tables with mixed-case names
    // if desired, but requires consistent quoting when referencing them.
    String sql =
        String.format(
            "CREATE TABLE %s (%s)", quotedName(tableName), String.join(", ", columnDefinitions));
    if (properties.containsKey(TABLESPACE) && StringUtils.isNotBlank(properties.get(TABLESPACE))) {
      sql = sql + " TABLESPACE " + quotedName(properties.get(TABLESPACE));
    }
    return sql;
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
      Index[] indexes,
      SortOrder[] sortOrders) {
    Preconditions.checkArgument(
        ArrayUtils.isEmpty(sortOrders), "Oracle does not support sort orders in #600.");
    try (Connection connection = getConnection(databaseName)) {
      String createSql =
          generateCreateTableSql(
              tableName,
              columns,
              comment,
              properties,
              partitioning,
              distribution,
              indexes,
              sortOrders);
      JdbcConnectorUtils.executeUpdate(connection, createSql);
      applyTableAndColumnComments(connection, tableName, comment, columns);
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generateDropTableSql(String tableName) {
    return String.format("DROP TABLE %s PURGE", quotedName(tableName));
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    return String.format("DROP TABLE %s PURGE", quotedName(tableName));
  }

  @Override
  protected Map<String, String> getTableProperties(Connection connection, String tableName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(GET_TABLE_PROPERTIES_SQL)) {
      statement.setString(1, connection.getSchema());
      statement.setString(2, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new NoSuchTableException(
              "Table %s does not exist in %s.", tableName, connection.getSchema());
        }
        return Map.of(
            TABLESPACE, StringUtils.defaultString(resultSet.getString("TABLESPACE_NAME")),
            PARTITIONED, StringUtils.defaultString(resultSet.getString("PARTITIONED")),
            ROW_MOVEMENT, StringUtils.defaultString(resultSet.getString("ROW_MOVEMENT")),
            COMPRESSION, StringUtils.defaultString(resultSet.getString("COMPRESSION")),
            COMMENT_KEY, StringUtils.defaultString(resultSet.getString("COMMENTS")));
      }
    }
  }

  @Override
  protected List<Index> getIndexes(Connection connection, String databaseName, String tableName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(GET_INDEXES_SQL)) {
      statement.setString(1, databaseName);
      statement.setString(2, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Index> indexes = new ArrayList<>();
        String currentName = null;
        String currentType = null;
        List<String> currentColumns = new ArrayList<>();
        while (resultSet.next()) {
          String name = resultSet.getString("CONSTRAINT_NAME");
          String type = resultSet.getString("CONSTRAINT_TYPE");
          if (!Objects.equals(currentName, name)) {
            appendIndex(indexes, currentName, currentType, currentColumns);
            currentName = name;
            currentType = type;
            currentColumns = new ArrayList<>();
          }
          currentColumns.add(resultSet.getString("COLUMN_NAME"));
        }
        appendIndex(indexes, currentName, currentType, currentColumns);
        return indexes;
      }
    }
  }

  @Override
  protected Transform[] getTablePartitioning(
      Connection connection, String databaseName, String tableName) throws SQLException {
    return Transforms.EMPTY_TRANSFORM;
  }

  @Override
  protected void correctJdbcTableFields(
      Connection connection,
      String databaseName,
      String tableName,
      JdbcTable.Builder jdbcTableBuilder)
      throws SQLException {
    String comment =
        jdbcTableBuilder.properties().getOrDefault(COMMENT_KEY, jdbcTableBuilder.comment());
    if (StringUtils.isNotBlank(comment)) {
      jdbcTableBuilder.withComment(comment);
    }
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    throw new UnsupportedOperationException("Oracle table rename is tracked by #601");
  }

  @Override
  protected String generateAlterTableSql(
      String databaseName, String tableName, TableChange... changes) {
    throw new UnsupportedOperationException("Oracle table alter is tracked by #601");
  }

  @Override
  protected Connection getConnection(String databaseName) throws SQLException {
    Connection connection = dataSource.getConnection();
    String schemaName = normalizeSchemaName(databaseName);
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          String.format("ALTER SESSION SET CURRENT_SCHEMA = %s", quotedName(schemaName)));
      connection.setSchema(schemaName);
      return connection;
    } catch (SQLException e) {
      try {
        connection.close();
      } catch (SQLException closeException) {
        e.addSuppressed(closeException);
      }
      throw e;
    }
  }

  @Override
  protected ResultSet getTables(Connection connection) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getTables(null, connection.getSchema(), null, new String[] {"TABLE"});
  }

  @Override
  protected ResultSet getTable(Connection connection, String databaseName, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getTables(null, connection.getSchema(), tableName, new String[] {"TABLE"});
  }

  @Override
  protected ResultSet getColumns(Connection connection, String databaseName, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getColumns(null, connection.getSchema(), tableName, null);
  }

  @Override
  protected boolean getAutoIncrementInfo(ResultSet resultSet) throws SQLException {
    return false;
  }

  private List<String> buildConstraintDefinitions(Index[] indexes) {
    if (ArrayUtils.isEmpty(indexes)) {
      return Collections.emptyList();
    }
    return Arrays.stream(indexes)
        .map(
            index -> {
              String columns = formatIndexColumns(index.fieldNames());
              if (index.type() == Index.IndexType.PRIMARY_KEY) {
                if (StringUtils.isNotBlank(index.name())) {
                  return String.format(
                      "CONSTRAINT %s PRIMARY KEY (%s)", quotedName(index.name()), columns);
                }
                return String.format("PRIMARY KEY (%s)", columns);
              }
              if (index.type() == Index.IndexType.UNIQUE_KEY) {
                if (StringUtils.isNotBlank(index.name())) {
                  return String.format(
                      "CONSTRAINT %s UNIQUE (%s)", quotedName(index.name()), columns);
                }
                return String.format("UNIQUE (%s)", columns);
              }
              throw new UnsupportedOperationException(
                  "Oracle supports only PRIMARY_KEY and UNIQUE_KEY indexes in #600.");
            })
        .collect(Collectors.toList());
  }

  private String formatIndexColumns(String[][] fieldNames) {
    Preconditions.checkArgument(ArrayUtils.isNotEmpty(fieldNames), "Index fields cannot be empty.");
    return Arrays.stream(fieldNames)
        .map(
            names -> {
              Preconditions.checkArgument(
                  names.length == 1, "Oracle does not support complex index fields.");
              return quotedName(names[0]);
            })
        .collect(Collectors.joining(", "));
  }

  private void appendIndex(List<Index> indexes, String name, String type, List<String> columns) {
    if (StringUtils.isBlank(name) || columns.isEmpty()) {
      return;
    }
    String[][] fieldNames =
        columns.stream().map(col -> new String[] {col}).toArray(String[][]::new);
    if ("P".equals(type)) {
      indexes.add(Indexes.primary(name, fieldNames));
    } else if ("U".equals(type)) {
      indexes.add(Indexes.unique(name, fieldNames));
    }
  }

  private void applyTableAndColumnComments(
      Connection connection, String tableName, String comment, JdbcColumn[] columns)
      throws SQLException {
    if (StringUtils.isNotBlank(comment)) {
      String tableCommentSql =
          String.format(
              "COMMENT ON TABLE %s IS '%s'", quotedName(tableName), escapeSqlComment(comment));
      JdbcConnectorUtils.executeUpdate(connection, tableCommentSql);
    }

    for (JdbcColumn column : columns) {
      if (StringUtils.isNotBlank(column.comment())) {
        String columnCommentSql =
            String.format(
                "COMMENT ON COLUMN %s.%s IS '%s'",
                quotedName(tableName),
                quotedName(column.name()),
                escapeSqlComment(column.comment()));
        JdbcConnectorUtils.executeUpdate(connection, columnCommentSql);
      }
    }
  }

  private static String escapeSqlComment(String value) {
    return value.replace("'", "''");
  }

  /**
   * Wraps an identifier in double quotes, preserving its case. Quoted identifiers are intentionally
   * case-sensitive per Oracle's SQL semantics; callers must use consistent casing for
   * table/column/index names across create and load. Schema names are separately normalized to
   * uppercase via {@link #normalizeSchemaName(String)} because Oracle schemas map to users.
   */
  private static String quotedName(String name) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "Identifier name cannot be null or blank.");
    return DOUBLE_QUOTE + name.replace(DOUBLE_QUOTE, DOUBLE_QUOTE + DOUBLE_QUOTE) + DOUBLE_QUOTE;
  }

  private static String normalizeSchemaName(String databaseName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(databaseName), "Schema name cannot be null or blank.");
    return databaseName.toUpperCase(Locale.ROOT);
  }
}
