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
import com.google.common.collect.Lists;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.partitions.ListPartition;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Type;

/** Table operations for Oracle. */
public class OracleTableOperations extends JdbcTableOperations {

  private static final String DOUBLE_QUOTE = "\"";
  private static final String GET_TABLE_PROPERTIES_SQL =
      "SELECT t.TABLESPACE_NAME, t.PARTITIONED, t.ROW_MOVEMENT, t.COMPRESSION, c.COMMENTS "
          + "FROM ALL_TABLES t LEFT JOIN ALL_TAB_COMMENTS c "
          + "ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME "
          + "WHERE t.OWNER = ? AND t.TABLE_NAME = ?";
  // Oracle's JDBC driver does not populate REMARKS in DatabaseMetaData.getColumns(); column
  // comments live in ALL_COL_COMMENTS and must be fetched explicitly (see issue #855).
  private static final String GET_COLUMN_COMMENTS_SQL =
      "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?";
  private static final String GET_INDEXES_SQL =
      "SELECT ac.CONSTRAINT_NAME, ac.CONSTRAINT_TYPE, acc.COLUMN_NAME, acc.POSITION "
          + "FROM ALL_CONSTRAINTS ac JOIN ALL_CONS_COLUMNS acc "
          + "ON ac.OWNER = acc.OWNER AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME "
          + "WHERE ac.OWNER = ? AND ac.TABLE_NAME = ? "
          + "AND ac.CONSTRAINT_TYPE IN ('P', 'U') "
          + "ORDER BY ac.CONSTRAINT_NAME, acc.POSITION";
  private static final String GET_PARTITION_TYPE_SQL =
      "SELECT PARTITIONING_TYPE, PARTITION_COUNT FROM ALL_PART_TABLES "
          + "WHERE OWNER = ? AND TABLE_NAME = ?";
  private static final String GET_PARTITION_COLUMNS_SQL =
      "SELECT COLUMN_NAME FROM ALL_PART_KEY_COLUMNS "
          + "WHERE OWNER = ? AND NAME = ? ORDER BY COLUMN_POSITION";

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
        distribution == null || Distributions.NONE.equals(distribution),
        "Oracle does not support table distribution.");

    List<String> columnDefinitions = new ArrayList<>();
    for (JdbcColumn column : columns) {
      if (column.autoIncrement()) {
        throw new UnsupportedOperationException("Oracle does not support AUTO_INCREMENT column.");
      }

      StringBuilder columnSql = new StringBuilder();
      columnSql
          .append(quotedName(column.name()))
          .append(" ")
          .append(typeConverter.fromGravitino(column.dataType()));
      // Oracle requires DEFAULT to precede NOT NULL in a column definition (ORA-03076 otherwise).
      if (!DEFAULT_VALUE_NOT_SET.equals(column.defaultValue())) {
        columnSql
            .append(" DEFAULT ")
            .append(columnDefaultValueConverter.fromGravitino(column.defaultValue()));
      }
      if (!column.nullable()) {
        columnSql.append(" NOT NULL");
      }
      columnDefinitions.add(columnSql.toString());
    }

    columnDefinitions.addAll(buildConstraintDefinitions(indexes));

    // Quote the table name and column names to preserve case sensitivity, as Oracle treats unquoted
    // identifiers as uppercase by default. This allows users to create tables with mixed-case names
    // if desired, but requires consistent quoting when referencing them.
    StringBuilder sql =
        new StringBuilder(
            String.format(
                "CREATE TABLE %s (%s)",
                quotedName(tableName), String.join(", ", columnDefinitions)));
    if (properties.containsKey(TABLESPACE) && StringUtils.isNotBlank(properties.get(TABLESPACE))) {
      sql.append(" TABLESPACE ").append(quotedName(properties.get(TABLESPACE)));
    }
    if (!ArrayUtils.isEmpty(partitioning)) {
      sql.append(generatePartitionClauseSql(partitioning));
    }
    return sql.toString();
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
    try (PreparedStatement typeStmt = connection.prepareStatement(GET_PARTITION_TYPE_SQL)) {
      typeStmt.setString(1, databaseName);
      typeStmt.setString(2, tableName);
      try (ResultSet typeRs = typeStmt.executeQuery()) {
        if (!typeRs.next()) {
          return Transforms.EMPTY_TRANSFORM;
        }
        String partitionType = typeRs.getString("PARTITIONING_TYPE");
        int partitionCount = typeRs.getInt("PARTITION_COUNT");
        String[] columns = getPartitionKeyColumns(connection, databaseName, tableName);
        if (columns.length == 0) {
          return Transforms.EMPTY_TRANSFORM;
        }
        switch (partitionType) {
          case "RANGE":
            if (columns.length != 1) {
              throw new UnsupportedOperationException(
                  "Oracle composite RANGE partitioning is not supported.");
            }
            return new Transform[] {Transforms.range(columns)};
          case "LIST":
            String[][] listColumns =
                Arrays.stream(columns).map(c -> new String[] {c}).toArray(String[][]::new);
            return new Transform[] {Transforms.list(listColumns)};
          case "HASH":
            String[][] hashColumns =
                Arrays.stream(columns).map(c -> new String[] {c}).toArray(String[][]::new);
            return new Transform[] {Transforms.bucket(partitionCount, hashColumns)};
          default:
            return Transforms.EMPTY_TRANSFORM;
        }
      }
    }
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

    correctColumnComments(connection, tableName, jdbcTableBuilder);
  }

  /**
   * Populates column comments from Oracle's {@code ALL_COL_COMMENTS} view. The base JDBC metadata
   * path reads comments from {@code REMARKS}, which the Oracle driver leaves empty, so loaded
   * columns would otherwise always have a {@code null} comment (see issue #855).
   *
   * @param connection the JDBC connection.
   * @param tableName the table whose column comments are fetched.
   * @param jdbcTableBuilder the builder holding the already-loaded columns to be corrected.
   * @throws SQLException if the column-comment query fails.
   */
  private void correctColumnComments(
      Connection connection, String tableName, JdbcTable.Builder jdbcTableBuilder)
      throws SQLException {
    Column[] columns = jdbcTableBuilder.columns();
    if (columns == null || columns.length == 0) {
      return;
    }

    Map<String, String> columnComments = getColumnComments(connection, tableName);
    if (columnComments.isEmpty()) {
      return;
    }

    Column[] corrected = columns.clone();
    for (int i = 0; i < corrected.length; i++) {
      Column column = corrected[i];
      String comment = columnComments.get(column.name());
      if (StringUtils.isNotBlank(comment) && !comment.equals(column.comment())) {
        corrected[i] =
            JdbcColumn.builder()
                .withName(column.name())
                .withType(column.dataType())
                .withComment(comment)
                .withNullable(column.nullable())
                .withAutoIncrement(column.autoIncrement())
                .withDefaultValue(column.defaultValue())
                .build();
      }
    }
    jdbcTableBuilder.withColumns(corrected);
  }

  private Map<String, String> getColumnComments(Connection connection, String tableName)
      throws SQLException {
    Map<String, String> columnComments = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(GET_COLUMN_COMMENTS_SQL)) {
      statement.setString(1, connection.getSchema());
      statement.setString(2, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String columnName = resultSet.getString("COLUMN_NAME");
          String comment = resultSet.getString("COMMENTS");
          if (StringUtils.isNotBlank(comment)) {
            columnComments.put(columnName, comment);
          }
        }
      }
    }
    return columnComments;
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    return String.format(
        "ALTER TABLE %s RENAME TO %s", quotedName(oldTableName), quotedName(newTableName));
  }

  @Override
  protected String generateAlterTableSql(
      String databaseName, String tableName, TableChange... changes) {
    List<String> alterSqlList = generateAlterTableSqlList(databaseName, tableName, changes);
    if (alterSqlList.isEmpty()) {
      return "";
    }
    return String.join("\n", alterSqlList);
  }

  @Override
  public void alterTable(String databaseName, String tableName, TableChange... changes)
      throws NoSuchTableException {
    LOG.info("Attempting to alter table {} from schema {}", tableName, databaseName);
    List<String> alterSqlList = generateAlterTableSqlList(databaseName, tableName, changes);
    if (alterSqlList.isEmpty()) {
      LOG.info("No table changes to apply for {} from schema {}", tableName, databaseName);
      return;
    }
    List<String> executedSqlList = new ArrayList<>();
    String failedSql = null;
    // Oracle has limited ALTER TABLE capabilities compared to some other databases, so we generate
    // individual ALTER statements for each change and execute them sequentially. Note that some
    // changes (like dropping a column) may fail if there are dependent objects, and Oracle does not
    // support transactional DDL, so partial changes may be committed even if a later change fails.
    // We log each executed statement to help with troubleshooting in such cases.
    try (Connection connection = getConnection(databaseName)) {
      for (String sql : alterSqlList) {
        failedSql = sql;
        JdbcConnectorUtils.executeUpdate(connection, sql);
        executedSqlList.add(sql);
      }
      LOG.info("Altered table {} from schema {}", tableName, databaseName);
    } catch (final SQLException se) {
      LOG.error(
          "Failed to alter table {} from schema {} with SQL: {}. "
              + "Already executed SQLs may have been committed by Oracle: {}",
          tableName,
          databaseName,
          failedSql,
          executedSqlList,
          se);
      throw exceptionMapper.toGravitinoException(se);
    }
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
  public List<String> listTables(String databaseName) throws NoSuchSchemaException {
    // Oracle returns TABLE_CAT=null in DatabaseMetaData.getTables, so the catalog-based filter in
    // JdbcTableOperations.listTables would drop every row. Filter by TABLE_SCHEM (== Oracle owner)
    // instead.
    List<String> names = Lists.newArrayList();
    try (Connection connection = getConnection(databaseName);
        ResultSet tables = getTables(connection)) {
      String schema = connection.getSchema();
      while (tables.next()) {
        if (Objects.equals(tables.getString("TABLE_SCHEM"), schema)) {
          names.add(tables.getString("TABLE_NAME"));
        }
      }
      return names;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
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

  private String[] getPartitionKeyColumns(
      Connection connection, String databaseName, String tableName) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement stmt = connection.prepareStatement(GET_PARTITION_COLUMNS_SQL)) {
      stmt.setString(1, databaseName);
      stmt.setString(2, tableName);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString("COLUMN_NAME"));
        }
      }
    }
    return columns.toArray(new String[0]);
  }

  private String generatePartitionClauseSql(Transform[] partitioning) {
    Preconditions.checkArgument(
        partitioning.length == 1, "Oracle supports single-level partitioning only.");
    Transform transform = partitioning[0];
    if (transform instanceof Transforms.RangeTransform) {
      return generateRangePartitionSql((Transforms.RangeTransform) transform);
    }
    if (transform instanceof Transforms.ListTransform) {
      return generateListPartitionSql((Transforms.ListTransform) transform);
    }
    if (transform instanceof Transforms.BucketTransform) {
      return generateHashPartitionSql((Transforms.BucketTransform) transform);
    }
    throw new UnsupportedOperationException(
        "Oracle supports only RANGE, LIST, and HASH partitioning.");
  }

  private String generateRangePartitionSql(Transforms.RangeTransform rangeTransform) {
    Preconditions.checkArgument(
        rangeTransform.fieldName().length == 1,
        "Oracle RANGE partition supports only a single column.");
    String col = quotedName(rangeTransform.fieldName()[0]);
    StringBuilder sb = new StringBuilder(String.format(" PARTITION BY RANGE (%s)", col));
    RangePartition[] assignments = rangeTransform.assignments();
    if (ArrayUtils.isNotEmpty(assignments)) {
      String partitions =
          Arrays.stream(assignments)
              .map(
                  p -> {
                    validateRangePartition(p);
                    return String.format(
                        "PARTITION %s VALUES LESS THAN (%s)",
                        quotedName(p.name()), formatRangeUpperBound(p.upper()));
                  })
              .collect(Collectors.joining(", "));
      sb.append(" (").append(partitions).append(")");
    }
    return sb.toString();
  }

  private String generateListPartitionSql(Transforms.ListTransform listTransform) {
    String[][] fieldNames = listTransform.fieldNames();
    String cols = formatPartitionColumns(fieldNames);
    StringBuilder sb = new StringBuilder(String.format(" PARTITION BY LIST (%s)", cols));
    ListPartition[] assignments = listTransform.assignments();
    if (ArrayUtils.isNotEmpty(assignments)) {
      String partitions =
          Arrays.stream(assignments)
              .map(
                  p ->
                      String.format(
                          "PARTITION %s VALUES (%s)",
                          quotedName(p.name()),
                          formatListPartitionValues(p.lists(), fieldNames.length)))
              .collect(Collectors.joining(", "));
      sb.append(" (").append(partitions).append(")");
    }
    return sb.toString();
  }

  private String generateHashPartitionSql(Transforms.BucketTransform bucketTransform) {
    String cols = formatPartitionColumns(bucketTransform.fieldNames());
    return String.format(
        " PARTITION BY HASH (%s) PARTITIONS %d", cols, bucketTransform.numBuckets());
  }

  private String formatPartitionColumns(String[][] fieldNames) {
    Preconditions.checkArgument(
        ArrayUtils.isNotEmpty(fieldNames), "Partition fields cannot be empty.");
    return Arrays.stream(fieldNames)
        .map(
            names -> {
              Preconditions.checkArgument(
                  names.length == 1, "Oracle does not support nested partition fields.");
              return quotedName(names[0]);
            })
        .collect(Collectors.joining(", "));
  }

  private void validateRangePartition(RangePartition partition) {
    Preconditions.checkArgument(
        partition.lower() == null || Objects.equals(partition.lower(), Literals.NULL),
        "Oracle RANGE partition does not support explicit lower bounds: %s",
        partition.name());
  }

  private String formatRangeUpperBound(Literal<?> upper) {
    if (upper == null || Literals.NULL.equals(upper)) {
      return "MAXVALUE";
    }
    return columnDefaultValueConverter.fromGravitino(upper);
  }

  private String formatListPartitionValues(Literal<?>[][] lists, int numCols) {
    Preconditions.checkArgument(
        ArrayUtils.isNotEmpty(lists), "List partition values cannot be empty.");
    return Arrays.stream(lists)
        .map(
            row -> {
              Preconditions.checkArgument(
                  row.length == numCols,
                  "List partition value count must match partition column count.");
              if (numCols == 1) {
                return formatPartitionLiteral(row[0]);
              }
              return "("
                  + Arrays.stream(row)
                      .map(this::formatPartitionLiteral)
                      .collect(Collectors.joining(", "))
                  + ")";
            })
        .collect(Collectors.joining(", "));
  }

  private String formatPartitionLiteral(Literal<?> literal) {
    if (Literals.NULL.equals(literal)) {
      return "NULL";
    }
    return columnDefaultValueConverter.fromGravitino(literal);
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

  private List<String> generateAlterTableSqlList(
      String databaseName, String tableName, TableChange... changes) {
    JdbcTable lazyLoadTable = null;
    String qualifiedTableName = qualifiedTableName(databaseName, tableName);
    List<String> sqlList = new ArrayList<>();

    for (TableChange change : changes) {
      if (change instanceof TableChange.AddColumn) {
        TableChange.AddColumn addColumn = (TableChange.AddColumn) change;
        sqlList.add(generateAddColumnSql(qualifiedTableName, addColumn));
        if (StringUtils.isNotBlank(addColumn.getComment())) {
          sqlList.add(
              generateUpdateColumnCommentSql(
                  qualifiedTableName, addColumn.getFieldName(), addColumn.getComment()));
        }
      } else if (change instanceof TableChange.UpdateColumnType) {
        TableChange.UpdateColumnType updateColumnType = (TableChange.UpdateColumnType) change;
        sqlList.add(
            generateModifyColumnSql(
                qualifiedTableName,
                updateColumnType.fieldName(),
                null,
                null,
                updateColumnType.getNewDataType()));
      } else if (change instanceof TableChange.UpdateColumnDefaultValue) {
        TableChange.UpdateColumnDefaultValue updateDefault =
            (TableChange.UpdateColumnDefaultValue) change;
        sqlList.add(
            generateModifyColumnSql(
                qualifiedTableName,
                updateDefault.fieldName(),
                null,
                updateDefault.getNewDefaultValue(),
                null));
      } else if (change instanceof TableChange.UpdateColumnNullability) {
        TableChange.UpdateColumnNullability updateNullability =
            (TableChange.UpdateColumnNullability) change;
        sqlList.add(
            generateModifyColumnSql(
                qualifiedTableName,
                updateNullability.fieldName(),
                updateNullability.nullable(),
                null,
                null));
      } else if (change instanceof TableChange.UpdateColumnComment) {
        TableChange.UpdateColumnComment updateColumnComment =
            (TableChange.UpdateColumnComment) change;
        sqlList.add(
            generateUpdateColumnCommentSql(
                qualifiedTableName,
                updateColumnComment.fieldName(),
                updateColumnComment.getNewComment()));
      } else if (change instanceof TableChange.DeleteColumn) {
        TableChange.DeleteColumn deleteColumn = (TableChange.DeleteColumn) change;
        if (Boolean.TRUE.equals(deleteColumn.getIfExists())) {
          lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
        }
        String deleteSql = generateDeleteColumnSql(qualifiedTableName, deleteColumn, lazyLoadTable);
        if (StringUtils.isNotBlank(deleteSql)) {
          sqlList.add(deleteSql);
        }
      } else if (change instanceof TableChange.RenameColumn) {
        TableChange.RenameColumn renameColumn = (TableChange.RenameColumn) change;
        sqlList.add(generateRenameColumnSql(qualifiedTableName, renameColumn));
      } else if (change instanceof TableChange.UpdateComment) {
        TableChange.UpdateComment updateComment = (TableChange.UpdateComment) change;
        sqlList.add(
            String.format(
                "COMMENT ON TABLE %s IS '%s'",
                qualifiedTableName,
                escapeSqlComment(StringUtils.defaultString(updateComment.getNewComment()))));
      } else if (change instanceof TableChange.UpdateColumnPosition) {
        throw new UnsupportedOperationException("Oracle does not support reordering columns.");
      } else if (change instanceof TableChange.UpdateColumnAutoIncrement) {
        throw new UnsupportedOperationException("Oracle does not support AUTO_INCREMENT column.");
      } else if (change instanceof TableChange.AddIndex) {
        TableChange.AddIndex addIndex = (TableChange.AddIndex) change;
        sqlList.add(generateAddConstraintSql(qualifiedTableName, addIndex));
      } else if (change instanceof TableChange.DeleteIndex) {
        TableChange.DeleteIndex deleteIndex = (TableChange.DeleteIndex) change;
        if (deleteIndex.isIfExists()) {
          lazyLoadTable = getOrCreateTable(databaseName, tableName, lazyLoadTable);
          if (!indexExists(lazyLoadTable, deleteIndex.getName())) {
            continue;
          }
        }
        sqlList.add(generateDropConstraintSql(qualifiedTableName, deleteIndex.getName()));
      } else if (change instanceof TableChange.SetProperty
          || change instanceof TableChange.RemoveProperty) {
        throw new UnsupportedOperationException(
            "Oracle does not support table property changes: " + change.getClass().getSimpleName());
      } else {
        throw new UnsupportedOperationException(
            "Unsupported table change type: " + change.getClass().getSimpleName());
      }
    }

    return sqlList;
  }

  private String generateAddColumnSql(String qualifiedTableName, TableChange.AddColumn addColumn) {
    Preconditions.checkArgument(
        addColumn.getFieldName().length == 1, "Oracle does not support nested column names.");
    Preconditions.checkArgument(
        addColumn.getPosition() == TableChange.ColumnPosition.defaultPos(),
        "Oracle does not support specifying column position.");
    if (addColumn.isAutoIncrement()) {
      throw new UnsupportedOperationException("Oracle does not support AUTO_INCREMENT column.");
    }
    String columnDefinition =
        buildColumnDefinition(
            addColumn.getFieldName()[0],
            addColumn.getDataType(),
            addColumn.isNullable(),
            addColumn.getDefaultValue());
    return String.format("ALTER TABLE %s ADD (%s)", qualifiedTableName, columnDefinition);
  }

  private String generateModifyColumnSql(
      String qualifiedTableName,
      String[] fieldName,
      Boolean nullable,
      Expression defaultValue,
      Type type) {
    Preconditions.checkArgument(
        fieldName.length == 1, "Oracle does not support nested column names.");

    StringBuilder columnDefinition = new StringBuilder();
    columnDefinition.append(quotedName(fieldName[0]));
    if (type != null) {
      columnDefinition.append(" ").append(typeConverter.fromGravitino(type));
    }
    if (nullable != null) {
      if (nullable) {
        columnDefinition.append(" NULL");
      } else {
        columnDefinition.append(" NOT NULL");
      }
    }
    if (defaultValue != null) {
      if (DEFAULT_VALUE_NOT_SET.equals(defaultValue)) {
        columnDefinition.append(" DEFAULT NULL");
      } else {
        columnDefinition
            .append(" DEFAULT ")
            .append(columnDefaultValueConverter.fromGravitino(defaultValue));
      }
    }
    return String.format("ALTER TABLE %s MODIFY (%s)", qualifiedTableName, columnDefinition);
  }

  private String generateDeleteColumnSql(
      String qualifiedTableName, TableChange.DeleteColumn deleteColumn, JdbcTable table) {
    Preconditions.checkArgument(
        deleteColumn.fieldName().length == 1, "Oracle does not support nested column names.");
    String columnName = deleteColumn.fieldName()[0];
    if (Boolean.TRUE.equals(deleteColumn.getIfExists()) && !columnExists(table, columnName)) {
      return "";
    }
    return String.format(
        "ALTER TABLE %s DROP COLUMN %s", qualifiedTableName, quotedName(columnName));
  }

  private String generateRenameColumnSql(
      String qualifiedTableName, TableChange.RenameColumn renameColumn) {
    Preconditions.checkArgument(
        renameColumn.fieldName().length == 1, "Oracle does not support nested column names.");
    return String.format(
        "ALTER TABLE %s RENAME COLUMN %s TO %s",
        qualifiedTableName,
        quotedName(renameColumn.fieldName()[0]),
        quotedName(renameColumn.getNewName()));
  }

  private String generateUpdateColumnCommentSql(
      String qualifiedTableName, String[] fieldName, String comment) {
    Preconditions.checkArgument(
        fieldName.length == 1, "Oracle does not support nested column names.");
    if (comment == null) {
      return String.format(
          "COMMENT ON COLUMN %s.%s IS NULL", qualifiedTableName, quotedName(fieldName[0]));
    }
    return String.format(
        "COMMENT ON COLUMN %s.%s IS '%s'",
        qualifiedTableName, quotedName(fieldName[0]), escapeSqlComment(comment));
  }

  private String generateAddConstraintSql(
      String qualifiedTableName, TableChange.AddIndex addIndex) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(addIndex.getName()), "Oracle constraint requires a name.");
    String columns = formatIndexColumns(addIndex.getFieldNames());
    if (addIndex.getType() == Index.IndexType.PRIMARY_KEY) {
      return String.format(
          "ALTER TABLE %s ADD CONSTRAINT %s PRIMARY KEY (%s)",
          qualifiedTableName, quotedName(addIndex.getName()), columns);
    }
    if (addIndex.getType() == Index.IndexType.UNIQUE_KEY) {
      return String.format(
          "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (%s)",
          qualifiedTableName, quotedName(addIndex.getName()), columns);
    }
    throw new UnsupportedOperationException(
        "Oracle supports only PRIMARY_KEY and UNIQUE_KEY indexes.");
  }

  private String generateDropConstraintSql(String qualifiedTableName, String constraintName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(constraintName), "Oracle DROP CONSTRAINT requires a name.");
    return String.format(
        "ALTER TABLE %s DROP CONSTRAINT %s", qualifiedTableName, quotedName(constraintName));
  }

  private boolean indexExists(JdbcTable table, String indexName) {
    return Arrays.stream(table.index()).anyMatch(index -> Objects.equals(index.name(), indexName));
  }

  private String buildColumnDefinition(
      String columnName, Type type, boolean nullable, Expression defaultValue) {
    StringBuilder definition = new StringBuilder();
    definition.append(quotedName(columnName)).append(" ").append(typeConverter.fromGravitino(type));
    // Oracle requires DEFAULT to precede NOT NULL in a column definition (ORA-03076 otherwise).
    if (!DEFAULT_VALUE_NOT_SET.equals(defaultValue)) {
      definition
          .append(" DEFAULT ")
          .append(columnDefaultValueConverter.fromGravitino(defaultValue));
    }
    if (!nullable) {
      definition.append(" NOT NULL");
    }
    return definition.toString();
  }

  private String qualifiedTableName(String databaseName, String tableName) {
    return quotedName(normalizeSchemaName(databaseName)) + "." + quotedName(tableName);
  }
}
