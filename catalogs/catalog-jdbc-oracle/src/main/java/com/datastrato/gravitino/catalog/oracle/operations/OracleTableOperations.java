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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchColumnException;
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
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Table operations for Oracle. */
public class OracleTableOperations extends JdbcTableOperations {

  private static final int MAX_RANGE_EXPRESSIONS_PER_QUERY = 100;
  private static final int MAX_RANGE_EXPRESSION_QUERY_LENGTH = 30_000;
  private static final String EVALUATE_RANGE_EXPRESSIONS_SQL_PREFIX = "SELECT ";
  private static final String EVALUATE_RANGE_EXPRESSIONS_SQL_SUFFIX = " FROM SYS.DUAL";
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
  private static final String GET_PARTITION_COLUMN_TYPE_SQL =
      "SELECT DATA_TYPE, DATA_PRECISION, DATA_SCALE, CHAR_LENGTH FROM ALL_TAB_COLUMNS "
          + "WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
  private static final String GET_RANGE_PARTITIONS_SQL =
      "SELECT PARTITION_NAME, HIGH_VALUE FROM ALL_TAB_PARTITIONS "
          + "WHERE TABLE_OWNER = ? AND TABLE_NAME = ? ORDER BY PARTITION_POSITION";

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
          .append(OracleIdentifierUtil.quote(column.name()))
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

    // tableName has already been normalized to its canonical physical form by
    // OracleCatalogCapability.normalizeName before reaching this method, so it only needs quoting
    // here, not re-folding.
    StringBuilder sql =
        new StringBuilder(
            String.format(
                "CREATE TABLE %s (%s)",
                OracleIdentifierUtil.quote(tableName), String.join(", ", columnDefinitions)));
    if (properties.containsKey(TABLESPACE) && StringUtils.isNotBlank(properties.get(TABLESPACE))) {
      sql.append(" TABLESPACE ")
          .append(OracleIdentifierUtil.quotedName(properties.get(TABLESPACE)));
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
    return String.format("DROP TABLE %s PURGE", OracleIdentifierUtil.quote(tableName));
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    return String.format("DROP TABLE %s PURGE", OracleIdentifierUtil.quote(tableName));
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
            Type columnType =
                getPartitionColumnType(connection, databaseName, tableName, columns[0]);
            RangePartition[] assignments =
                getRangePartitions(connection, databaseName, tableName, columnType);
            return new Transform[] {Transforms.range(columns, assignments)};
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
        "ALTER TABLE %s RENAME TO %s",
        OracleIdentifierUtil.quote(oldTableName), OracleIdentifierUtil.quote(newTableName));
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
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          String.format(
              "ALTER SESSION SET CURRENT_SCHEMA = %s", OracleIdentifierUtil.quote(databaseName)));
      connection.setSchema(databaseName);
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
    // instead. Table names are returned exactly as Oracle stores them, with no synthetic quoting
    // added: Capability.normalizeName is not idempotent for a catalog whose folding depends on
    // whether the name was originally quoted, so this must not be run back through it (core's
    // TableNormalizeDispatcher.listTables deliberately does not re-normalize this result).
    List<String> names = Lists.newArrayList();
    try (Connection connection = getConnection(databaseName);
        ResultSet tables = getTables(connection)) {
      String schema = connection.getSchema();
      while (tables.next()) {
        if (!Objects.equals(tables.getString("TABLE_SCHEM"), schema)) {
          continue;
        }
        names.add(tables.getString("TABLE_NAME"));
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
  protected JdbcTable.Builder getTableBuilder(
      ResultSet tablesResult, String databaseName, String tableName) throws SQLException {
    // tableName has already been normalized to its canonical physical form by
    // OracleCatalogCapability.normalizeName before reaching this method, and the JDBC ResultSet was
    // queried using that same name, so it can be used as-is without any further conversion.
    return super.getTableBuilder(tablesResult, databaseName, tableName).withName(tableName);
  }

  @Override
  protected JdbcColumn.Builder getColumnBuilder(
      ResultSet columnsResult, String databaseName, String tableName) throws SQLException {
    JdbcColumn.Builder builder = super.getColumnBuilder(columnsResult, databaseName, tableName);
    if (builder != null) {
      builder.withName(columnsResult.getString("COLUMN_NAME"));
    }
    return builder;
  }

  @Override
  protected boolean getAutoIncrementInfo(ResultSet resultSet) throws SQLException {
    return false;
  }

  @VisibleForTesting
  Type getPartitionColumnType(
      Connection connection, String databaseName, String tableName, String columnName)
      throws SQLException {
    try (PreparedStatement stmt = connection.prepareStatement(GET_PARTITION_COLUMN_TYPE_SQL)) {
      stmt.setString(1, databaseName);
      stmt.setString(2, tableName);
      stmt.setString(3, columnName);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          throw new NoSuchColumnException(
              "Partition column %s does not exist in table %s.%s.",
              columnName, databaseName, tableName);
        }
        JdbcTypeConverter.JdbcTypeBean typeBean =
            new JdbcTypeConverter.JdbcTypeBean(rs.getString("DATA_TYPE"));
        Integer precision = rs.getObject("DATA_PRECISION", Integer.class);
        Integer charLength = rs.getObject("CHAR_LENGTH", Integer.class);
        Integer columnSize = precision;
        if (columnSize == null && charLength != null && charLength > 0) {
          columnSize = charLength;
        }
        typeBean.setColumnSize(columnSize);
        typeBean.setScale(rs.getObject("DATA_SCALE", Integer.class));
        return typeConverter.toGravitino(typeBean);
      }
    }
  }

  @VisibleForTesting
  Literal<?>[] evaluateRangeUpperBounds(
      Connection connection, List<String> expressions, Type columnType) throws SQLException {
    Literal<?>[] upperBounds = new Literal<?>[expressions.size()];
    List<Integer> finiteBoundPositions = new ArrayList<>();
    List<String> finiteBoundExpressions = new ArrayList<>();
    for (int i = 0; i < expressions.size(); i++) {
      String expression = expressions.get(i);
      if (StringUtils.isBlank(expression)) {
        throw new SQLException("Oracle returned an empty range partition bound expression.");
      }
      if ("MAXVALUE".equalsIgnoreCase(expression)) {
        upperBounds[i] = Literals.NULL;
      } else {
        finiteBoundPositions.add(i);
        finiteBoundExpressions.add(expression);
      }
    }

    if (finiteBoundExpressions.isEmpty()) {
      return upperBounds;
    }

    try (Statement statement = connection.createStatement()) {
      int batchStart = 0;
      while (batchStart < finiteBoundExpressions.size()) {
        StringBuilder sql = new StringBuilder(EVALUATE_RANGE_EXPRESSIONS_SQL_PREFIX);
        int batchEnd = batchStart;
        while (batchEnd < finiteBoundExpressions.size()
            && batchEnd - batchStart < MAX_RANGE_EXPRESSIONS_PER_QUERY) {
          String expression = finiteBoundExpressions.get(batchEnd);
          int separatorLength = batchEnd == batchStart ? 0 : 2;
          int projectedLength =
              sql.length()
                  + separatorLength
                  + expression.length()
                  + 2
                  + EVALUATE_RANGE_EXPRESSIONS_SQL_SUFFIX.length();
          if (batchEnd > batchStart && projectedLength > MAX_RANGE_EXPRESSION_QUERY_LENGTH) {
            break;
          }
          if (separatorLength > 0) {
            sql.append(", ");
          }
          sql.append('(').append(expression).append(')');
          batchEnd++;
        }
        sql.append(EVALUATE_RANGE_EXPRESSIONS_SQL_SUFFIX);

        try (ResultSet resultSet = statement.executeQuery(sql.toString())) {
          if (!resultSet.next()) {
            throw new SQLException("Oracle returned no row while evaluating partition bounds.");
          }
          for (int i = batchStart; i < batchEnd; i++) {
            int columnIndex = i - batchStart + 1;
            upperBounds[finiteBoundPositions.get(i)] =
                toRangeLiteral(resultSet, columnIndex, columnType);
          }
          if (resultSet.next()) {
            throw new SQLException(
                "Oracle returned multiple rows while evaluating partition bounds.");
          }
        }
        batchStart = batchEnd;
      }
    }
    return upperBounds;
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

  private RangePartition[] getRangePartitions(
      Connection connection, String databaseName, String tableName, Type columnType)
      throws SQLException {
    List<String> partitionNames = new ArrayList<>();
    List<String> highValueExpressions = new ArrayList<>();
    try (PreparedStatement stmt = connection.prepareStatement(GET_RANGE_PARTITIONS_SQL)) {
      stmt.setString(1, databaseName);
      stmt.setString(2, tableName);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          partitionNames.add(rs.getString("PARTITION_NAME"));
          highValueExpressions.add(StringUtils.trimToEmpty(rs.getString("HIGH_VALUE")));
        }
      }
    }

    Literal<?>[] upperBounds =
        evaluateRangeUpperBounds(connection, highValueExpressions, columnType);
    RangePartition[] partitions = new RangePartition[partitionNames.size()];
    for (int i = 0; i < partitionNames.size(); i++) {
      partitions[i] =
          Partitions.range(
              partitionNames.get(i), upperBounds[i], Literals.NULL, Collections.emptyMap());
    }
    return partitions;
  }

  private Literal<?> toRangeLiteral(ResultSet resultSet, int columnIndex, Type columnType)
      throws SQLException {
    Object value;
    switch (columnType.name()) {
      case BOOLEAN:
        BigDecimal booleanNumber = resultSet.getBigDecimal(columnIndex);
        if (booleanNumber == null) {
          value = null;
        } else if (BigDecimal.ZERO.compareTo(booleanNumber) == 0) {
          value = false;
        } else if (BigDecimal.ONE.compareTo(booleanNumber) == 0) {
          value = true;
        } else {
          // Oracle models booleans as NUMBER(1), which can also contain -9 through 9. Preserve
          // those external values instead of collapsing every non-zero bound to true.
          value = booleanNumber.stripTrailingZeros();
        }
        break;
      case BYTE:
        if (((Types.ByteType) columnType).signed()) {
          value = resultSet.getByte(columnIndex);
        } else {
          value = resultSet.getShort(columnIndex);
        }
        break;
      case SHORT:
        if (((Types.ShortType) columnType).signed()) {
          value = resultSet.getShort(columnIndex);
        } else {
          value = resultSet.getInt(columnIndex);
        }
        break;
      case INTEGER:
        if (((Types.IntegerType) columnType).signed()) {
          value = resultSet.getInt(columnIndex);
        } else {
          value = resultSet.getLong(columnIndex);
        }
        break;
      case LONG:
        if (((Types.LongType) columnType).signed()) {
          value = resultSet.getLong(columnIndex);
        } else {
          BigDecimal unsignedLongValue = resultSet.getBigDecimal(columnIndex);
          value = unsignedLongValue == null ? null : Decimal.of(unsignedLongValue);
        }
        break;
      case FLOAT:
        value = resultSet.getFloat(columnIndex);
        break;
      case DOUBLE:
        value = resultSet.getDouble(columnIndex);
        break;
      case DECIMAL:
        BigDecimal decimalValue = resultSet.getBigDecimal(columnIndex);
        Types.DecimalType decimalType = (Types.DecimalType) columnType;
        value =
            decimalValue == null
                ? null
                : Decimal.of(decimalValue, decimalType.precision(), decimalType.scale());
        break;
      case DATE:
        Date date = resultSet.getDate(columnIndex);
        value = date == null ? null : date.toLocalDate();
        break;
      case TIME:
        Time time = resultSet.getTime(columnIndex);
        value = time == null ? null : time.toLocalTime();
        break;
      case TIMESTAMP:
        Types.TimestampType timestampType = (Types.TimestampType) columnType;
        if (timestampType.hasTimeZone()) {
          value = resultSet.getObject(columnIndex, OffsetDateTime.class);
        } else {
          Timestamp timestamp = resultSet.getTimestamp(columnIndex);
          value = timestamp == null ? null : timestamp.toLocalDateTime();
        }
        break;
      case STRING:
      case VARCHAR:
      case FIXEDCHAR:
      case EXTERNAL:
        value = resultSet.getString(columnIndex);
        break;
      case FIXED:
      case BINARY:
        // Oracle JDBC exposes RAW values as stable hexadecimal text through getString. Keep that
        // representation because partition literals cross the REST boundary as strings, whereas a
        // byte array's toString() would lose the value.
        value = resultSet.getString(columnIndex);
        break;
      default:
        throw new UnsupportedOperationException(
            String.format(
                "Oracle range partition bounds do not support Gravitino type %s.",
                columnType.simpleString()));
    }

    if (value == null || resultSet.wasNull()) {
      throw new SQLException("Oracle returned NULL while evaluating a range partition bound.");
    }
    return Literals.of(value, columnType);
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
    String col = OracleIdentifierUtil.quote(rangeTransform.fieldName()[0]);
    StringBuilder sb = new StringBuilder(String.format(" PARTITION BY RANGE (%s)", col));
    RangePartition[] assignments = rangeTransform.assignments();
    Preconditions.checkArgument(
        ArrayUtils.isNotEmpty(assignments),
        "Oracle RANGE partitioning requires at least one assignment.");
    String partitions =
        Arrays.stream(assignments)
            .map(
                p -> {
                  validateRangePartition(p);
                  return String.format(
                      "PARTITION %s VALUES LESS THAN (%s)",
                      OracleIdentifierUtil.quote(p.name()), formatRangeUpperBound(p.upper()));
                })
            .collect(Collectors.joining(", "));
    sb.append(" (").append(partitions).append(")");
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
                          OracleIdentifierUtil.quote(p.name()),
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
    // Transform[] at CREATE TABLE time is normalized by core
    // (CapabilityHelpers.applyCapabilities(Transform[], ...)), so these field names are already in
    // physical form and only need quoting, not folding.
    return formatColumnRefs(
        fieldNames,
        "Partition fields cannot be empty.",
        "Oracle does not support nested partition fields.",
        OracleIdentifierUtil::quote);
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
              // Index[] at CREATE TABLE time is normalized by core
              // (CapabilityHelpers.applyCapabilities(Index[], ...)), so these field names are
              // already in physical form and only need quoting, not folding (unlike
              // TableChange.AddIndex's field names at ALTER TABLE time; see
              // formatIndexColumnsForAlter).
              String columns = formatIndexColumnsForCreate(index.fieldNames());
              if (index.type() == Index.IndexType.PRIMARY_KEY) {
                if (StringUtils.isNotBlank(index.name())) {
                  return String.format(
                      "CONSTRAINT %s PRIMARY KEY (%s)",
                      OracleIdentifierUtil.quote(index.name()), columns);
                }
                return String.format("PRIMARY KEY (%s)", columns);
              }
              if (index.type() == Index.IndexType.UNIQUE_KEY) {
                if (StringUtils.isNotBlank(index.name())) {
                  return String.format(
                      "CONSTRAINT %s UNIQUE (%s)",
                      OracleIdentifierUtil.quote(index.name()), columns);
                }
                return String.format("UNIQUE (%s)", columns);
              }
              throw new UnsupportedOperationException(
                  "Oracle supports only PRIMARY_KEY and UNIQUE_KEY indexes in #600.");
            })
        .collect(Collectors.toList());
  }

  private String formatIndexColumnsForCreate(String[][] fieldNames) {
    return formatColumnRefs(
        fieldNames,
        "Index fields cannot be empty.",
        "Oracle does not support complex index fields.",
        OracleIdentifierUtil::quote);
  }

  private String formatIndexColumnsForAlter(String[][] fieldNames) {
    // Unlike Index[] at CREATE TABLE time, TableChange.AddIndex/DeleteIndex (used for ALTER TABLE)
    // are never passed through Capability normalization by core
    // (CapabilityHelpers.applyCapabilities
    // only handles ColumnChange and RenameTable for TableChange), so these field names may still
    // need folding.
    return formatColumnRefs(
        fieldNames,
        "Index fields cannot be empty.",
        "Oracle does not support complex index fields.",
        OracleIdentifierUtil::quotedName);
  }

  private String formatColumnRefs(
      String[][] fieldNames,
      String emptyMessage,
      String nestedFieldMessage,
      Function<String, String> quoter) {
    Preconditions.checkArgument(ArrayUtils.isNotEmpty(fieldNames), emptyMessage);
    return Arrays.stream(fieldNames)
        .map(
            names -> {
              Preconditions.checkArgument(names.length == 1, nestedFieldMessage);
              return quoter.apply(names[0]);
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
              "COMMENT ON TABLE %s IS '%s'",
              OracleIdentifierUtil.quote(tableName), escapeSqlComment(comment));
      JdbcConnectorUtils.executeUpdate(connection, tableCommentSql);
    }

    for (JdbcColumn column : columns) {
      if (StringUtils.isNotBlank(column.comment())) {
        String columnCommentSql =
            String.format(
                "COMMENT ON COLUMN %s.%s IS '%s'",
                OracleIdentifierUtil.quote(tableName),
                OracleIdentifierUtil.quote(column.name()),
                escapeSqlComment(column.comment()));
        JdbcConnectorUtils.executeUpdate(connection, columnCommentSql);
      }
    }
  }

  private static String escapeSqlComment(String value) {
    return value.replace("'", "''");
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
    columnDefinition.append(OracleIdentifierUtil.quote(fieldName[0]));
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
        "ALTER TABLE %s DROP COLUMN %s",
        qualifiedTableName, OracleIdentifierUtil.quote(columnName));
  }

  private String generateRenameColumnSql(
      String qualifiedTableName, TableChange.RenameColumn renameColumn) {
    Preconditions.checkArgument(
        renameColumn.fieldName().length == 1, "Oracle does not support nested column names.");
    return String.format(
        "ALTER TABLE %s RENAME COLUMN %s TO %s",
        qualifiedTableName,
        OracleIdentifierUtil.quote(renameColumn.fieldName()[0]),
        OracleIdentifierUtil.quote(renameColumn.getNewName()));
  }

  private String generateUpdateColumnCommentSql(
      String qualifiedTableName, String[] fieldName, String comment) {
    Preconditions.checkArgument(
        fieldName.length == 1, "Oracle does not support nested column names.");
    if (comment == null) {
      return String.format(
          "COMMENT ON COLUMN %s.%s IS NULL",
          qualifiedTableName, OracleIdentifierUtil.quote(fieldName[0]));
    }
    return String.format(
        "COMMENT ON COLUMN %s.%s IS '%s'",
        qualifiedTableName, OracleIdentifierUtil.quote(fieldName[0]), escapeSqlComment(comment));
  }

  private String generateAddConstraintSql(
      String qualifiedTableName, TableChange.AddIndex addIndex) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(addIndex.getName()), "Oracle constraint requires a name.");
    String columns = formatIndexColumnsForAlter(addIndex.getFieldNames());
    if (addIndex.getType() == Index.IndexType.PRIMARY_KEY) {
      return String.format(
          "ALTER TABLE %s ADD CONSTRAINT %s PRIMARY KEY (%s)",
          qualifiedTableName, OracleIdentifierUtil.quote(addIndex.getName()), columns);
    }
    if (addIndex.getType() == Index.IndexType.UNIQUE_KEY) {
      return String.format(
          "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (%s)",
          qualifiedTableName, OracleIdentifierUtil.quote(addIndex.getName()), columns);
    }
    throw new UnsupportedOperationException(
        "Oracle supports only PRIMARY_KEY and UNIQUE_KEY indexes.");
  }

  private String generateDropConstraintSql(String qualifiedTableName, String constraintName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(constraintName), "Oracle DROP CONSTRAINT requires a name.");
    return String.format(
        "ALTER TABLE %s DROP CONSTRAINT %s",
        qualifiedTableName, OracleIdentifierUtil.quote(constraintName));
  }

  private boolean indexExists(JdbcTable table, String indexName) {
    // Index names are never folded by Gravitino core (there is no Scope.INDEX) and are quoted
    // case-preserving, exactly like Oracle's own case-sensitive quoted-identifier semantics, so
    // this must match exactly, not case-insensitively.
    return Arrays.stream(table.index()).anyMatch(index -> Objects.equals(index.name(), indexName));
  }

  private String buildColumnDefinition(
      String columnName, Type type, boolean nullable, Expression defaultValue) {
    StringBuilder definition = new StringBuilder();
    definition
        .append(OracleIdentifierUtil.quote(columnName))
        .append(" ")
        .append(typeConverter.fromGravitino(type));
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
    return OracleIdentifierUtil.quote(databaseName) + "." + OracleIdentifierUtil.quote(tableName);
  }
}
