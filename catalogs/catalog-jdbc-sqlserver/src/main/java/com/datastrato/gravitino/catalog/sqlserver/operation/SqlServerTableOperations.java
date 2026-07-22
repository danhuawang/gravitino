/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.datastrato.gravitino.catalog.sqlserver.utils.SqlServerUtils;
import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.bean.JdbcIndexBean;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.DatabaseOperation;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.operation.RequireDatabaseOperation;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;

/** Table operations for SQL Server catalog. */
public class SqlServerTableOperations extends JdbcTableOperations
    implements RequireDatabaseOperation {

  private static final String SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG =
      "SQL Server does not support nested column names.";

  private String database;
  private SqlServerSchemaOperations schemaOperations;

  // Visible for testing. Thread-safety note: Gravitino's TreeLock serializes WRITE operations
  // on the same schema path, and the base class generateCreateTableSql() signature has no schema
  // parameter. This is a framework-level limitation shared with PostgreSQL's similar pattern.
  String currentSchemaName;

  @Override
  public void initialize(
      DataSource dataSource,
      JdbcExceptionConverter exceptionMapper,
      JdbcTypeConverter jdbcTypeConverter,
      JdbcColumnDefaultValueConverter jdbcColumnDefaultValueConverter,
      Map<String, String> conf) {
    super.initialize(
        dataSource, exceptionMapper, jdbcTypeConverter, jdbcColumnDefaultValueConverter, conf);
    database = new JdbcConfig(conf).getJdbcDatabase();
    Preconditions.checkArgument(
        StringUtils.isNotBlank(database),
        "The `jdbc-database` configuration item is mandatory in SQL Server.");
  }

  @Override
  public void setDatabaseOperation(DatabaseOperation databaseOperation) {
    this.schemaOperations = (SqlServerSchemaOperations) databaseOperation;
  }

  @Override
  public List<String> listTables(String schemaName) throws NoSuchSchemaException {
    List<String> names = new ArrayList<>();
    try (Connection connection = getConnection(schemaName)) {
      if (!schemaOperations.schemaExists(connection, schemaName)) {
        throw new NoSuchSchemaException("No such schema: %s", schemaName);
      }
      // Filter out system-shipped tables (is_ms_shipped = 1) such as msreplication_options,
      // spt_fallback_db, etc. These are internal SQL Server tables that users should not manage.
      String sql =
          "SELECT t.name FROM sys.tables t "
              + "JOIN sys.schemas s ON t.schema_id = s.schema_id "
              + "WHERE s.name = ? AND t.is_ms_shipped = 0 ORDER BY t.name";
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, schemaName);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            names.add(rs.getString(1));
          }
        }
      }
      LOG.info("Finished listing tables size {} for schema {}", names.size(), schemaName);
      return names;
    } catch (SQLException se) {
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
    if (ArrayUtils.isNotEmpty(partitioning)) {
      throw new UnsupportedOperationException(
          "SQL Server does not support partitioning in Gravitino.");
    }
    Preconditions.checkArgument(
        Distributions.NONE.equals(distribution), "SQL Server does not support distribution.");
    Preconditions.checkArgument(
        properties == null || properties.isEmpty(),
        "SQL Server does not support table properties.");

    validateIncrementCol(columns, indexes);

    String schemaName = getSchemaName();
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append(
        String.format(
            "CREATE TABLE %s.%s (\n", quoteIdentifier(schemaName), quoteIdentifier(tableName)));

    // Add columns
    for (int i = 0; i < columns.length; i++) {
      JdbcColumn column = columns[i];
      sqlBuilder.append("    ").append(quoteIdentifier(column.name())).append(" ");
      appendColumnDefinition(column, sqlBuilder);
      if (i < columns.length - 1) {
        sqlBuilder.append(",");
      }
      sqlBuilder.append("\n");
    }

    // Add indexes (PRIMARY KEY, UNIQUE)
    appendIndexesSql(indexes, tableName, sqlBuilder);

    sqlBuilder.append(")");

    // Add extended properties for comments after CREATE TABLE
    if (StringUtils.isNotEmpty(comment)) {
      sqlBuilder.append(";\n");
      sqlBuilder.append(SqlServerUtils.generateAddTableCommentSql(schemaName, tableName, comment));
    }
    Arrays.stream(columns)
        .filter(col -> StringUtils.isNotEmpty(col.comment()))
        .forEach(
            col -> {
              sqlBuilder.append(";\n");
              sqlBuilder.append(
                  SqlServerUtils.generateAddColumnCommentSql(
                      schemaName, tableName, col.name(), col.comment()));
            });

    String result = sqlBuilder.toString();
    LOG.info("Generated create table:{} sql: {}", tableName, result);
    return result;
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    String schemaName = getSchemaName();
    return String.format(
        "EXEC sp_rename '%s.%s', '%s'",
        SqlServerUtils.escapeQuote(schemaName),
        SqlServerUtils.escapeQuote(oldTableName),
        SqlServerUtils.escapeQuote(newTableName));
  }

  @Override
  protected String generateDropTableSql(String tableName) {
    String schemaName = getSchemaName();
    return String.format(
        "DROP TABLE %s.%s", quoteIdentifier(schemaName), quoteIdentifier(tableName));
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    throw new UnsupportedOperationException("SQL Server does not support purge table.");
  }

  @Override
  protected String generateAlterTableSql(
      String schemaName, String tableName, TableChange... changes) {
    JdbcTable lazyLoadTable = null;
    List<String> alterSql = new ArrayList<>();

    for (TableChange change : changes) {
      if (change instanceof TableChange.UpdateComment) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.add(
            updateCommentDefinition((TableChange.UpdateComment) change, lazyLoadTable, schemaName));
      } else if (change instanceof TableChange.SetProperty) {
        throw new IllegalArgumentException("Set property is not supported yet.");
      } else if (change instanceof TableChange.RemoveProperty) {
        throw new IllegalArgumentException("Remove property is not supported yet.");
      } else if (change instanceof TableChange.AddColumn) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.addAll(
            addColumnFieldDefinition((TableChange.AddColumn) change, lazyLoadTable, schemaName));
      } else if (change instanceof TableChange.RenameColumn) {
        alterSql.add(
            renameColumnFieldDefinition((TableChange.RenameColumn) change, schemaName, tableName));
      } else if (change instanceof TableChange.UpdateColumnDefaultValue) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.add(
            updateColumnDefaultValueFieldDefinition(
                (TableChange.UpdateColumnDefaultValue) change, lazyLoadTable, schemaName));
      } else if (change instanceof TableChange.UpdateColumnType) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        alterSql.add(
            updateColumnTypeFieldDefinition(
                (TableChange.UpdateColumnType) change, lazyLoadTable, schemaName));
      } else if (change instanceof TableChange.UpdateColumnComment) {
        alterSql.add(
            updateColumnCommentFieldDefinition(
                (TableChange.UpdateColumnComment) change, schemaName, tableName));
      } else if (change instanceof TableChange.UpdateColumnPosition) {
        throw new IllegalArgumentException("SQL Server does not support column position.");
      } else if (change instanceof TableChange.DeleteColumn) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        String deleteColSql =
            deleteColumnFieldDefinition(
                (TableChange.DeleteColumn) change, lazyLoadTable, schemaName);
        if (StringUtils.isNotEmpty(deleteColSql)) {
          alterSql.add(deleteColSql);
        }
      } else if (change instanceof TableChange.UpdateColumnNullability) {
        lazyLoadTable = getOrCreateTable(schemaName, tableName, lazyLoadTable);
        validateUpdateColumnNullable((TableChange.UpdateColumnNullability) change, lazyLoadTable);
        alterSql.add(
            updateColumnNullabilityDefinition(
                (TableChange.UpdateColumnNullability) change, lazyLoadTable, schemaName));
      } else {
        throw new IllegalArgumentException(
            "Unsupported table change type: " + change.getClass().getName());
      }
    }

    if (alterSql.isEmpty()) {
      return "";
    }

    String result = String.join("\n", alterSql);
    LOG.info("Generated alter table:{}.{} sql: {}", schemaName, tableName, result);
    return result;
  }

  @Override
  protected JdbcTable.Builder getTableBuilder(
      ResultSet tablesResult, String databaseName, String tableName) throws SQLException {
    boolean found = false;
    JdbcTable.Builder builder = null;
    while (tablesResult.next() && !found) {
      String tableNameInResult = tablesResult.getString("TABLE_NAME");
      String tableSchemaInResult = tablesResult.getString("TABLE_SCHEM");
      if (Objects.equals(tableNameInResult, tableName)
          && Objects.equals(tableSchemaInResult, databaseName)) {
        builder = getBasicJdbcTableInfo(tablesResult).withDatabaseName(databaseName);
        found = true;
      }
    }
    if (!found) {
      throw new NoSuchTableException("Table %s does not exist in %s.", tableName, databaseName);
    }
    return builder;
  }

  @Override
  protected JdbcColumn.Builder getColumnBuilder(
      ResultSet columnsResult, String databaseName, String tableName) throws SQLException {
    JdbcColumn.Builder builder = null;
    if (Objects.equals(columnsResult.getString("TABLE_NAME"), tableName)
        && Objects.equals(columnsResult.getString("TABLE_SCHEM"), databaseName)) {
      builder = getBasicJdbcColumnInfo(columnsResult);
    }
    return builder;
  }

  @Override
  protected void correctJdbcTableFields(
      Connection connection,
      String databaseName,
      String tableName,
      JdbcTable.Builder jdbcTableBuilder)
      throws SQLException {
    // Query sys.columns for rich metadata: identity, comments, defaults.
    // Use QUOTENAME to safely handle special characters in schema/table names.
    String sql =
        "SELECT c.name AS COLUMN_NAME, t.name AS TYPE_NAME, "
            + "c.max_length, c.precision, c.scale, c.is_nullable, c.is_identity, "
            + "dc.definition AS COLUMN_DEF, "
            + "ep.value AS REMARKS "
            + "FROM sys.columns c "
            + "JOIN sys.types t ON c.user_type_id = t.user_type_id "
            + "LEFT JOIN sys.default_constraints dc "
            + "ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id "
            + "LEFT JOIN sys.extended_properties ep "
            + "ON ep.major_id = c.object_id AND ep.minor_id = c.column_id "
            + "AND ep.name = 'MS_Description' "
            + "WHERE c.object_id = OBJECT_ID(QUOTENAME(?) + N'.' + QUOTENAME(?)) "
            + "ORDER BY c.column_id";

    List<JdbcColumn> correctedColumns = new ArrayList<>();
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, databaseName);
      stmt.setString(2, tableName);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          String colName = rs.getString("COLUMN_NAME");
          String typeName = rs.getString("TYPE_NAME");
          int maxLength = rs.getInt("max_length");
          int precision = rs.getInt("precision");
          int scale = rs.getInt("scale");
          boolean nullable = rs.getBoolean("is_nullable");
          boolean isIdentity = rs.getBoolean("is_identity");
          String columnDef = rs.getString("COLUMN_DEF");
          String remarks = rs.getString("REMARKS");

          JdbcTypeConverter.JdbcTypeBean typeBean = new JdbcTypeConverter.JdbcTypeBean(typeName);
          configureTypeBean(typeBean, typeName, maxLength, precision, scale);

          Expression defaultValue =
              columnDefaultValueConverter.toGravitino(typeBean, columnDef, false, nullable);

          correctedColumns.add(
              JdbcColumn.builder()
                  .withName(colName)
                  .withType(typeConverter.toGravitino(typeBean))
                  .withComment(StringUtils.isEmpty(remarks) ? null : remarks)
                  .withNullable(nullable)
                  .withDefaultValue(defaultValue)
                  .withAutoIncrement(isIdentity)
                  .build());
        }
      }
    }

    if (!correctedColumns.isEmpty()) {
      jdbcTableBuilder.withColumns(correctedColumns.toArray(new JdbcColumn[0]));
    }

    // Correct table comment from extended properties
    String tableCommentSql =
        "SELECT ep.value AS TABLE_COMMENT "
            + "FROM sys.extended_properties ep "
            + "JOIN sys.tables t ON ep.major_id = t.object_id "
            + "JOIN sys.schemas s ON t.schema_id = s.schema_id "
            + "WHERE ep.minor_id = 0 AND ep.name = 'MS_Description' "
            + "AND s.name = ? AND t.name = ?";
    try (PreparedStatement stmt = connection.prepareStatement(tableCommentSql)) {
      stmt.setString(1, databaseName);
      stmt.setString(2, tableName);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          String tableComment = rs.getString("TABLE_COMMENT");
          if (StringUtils.isNotEmpty(tableComment)) {
            jdbcTableBuilder.withComment(tableComment);
          }
        }
      }
    }
  }

  @Override
  protected Connection getConnection(String schema) throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    connection.setSchema(schema);
    this.currentSchemaName = schema;
    return connection;
  }

  @Override
  protected List<Index> getIndexes(Connection connection, String databaseName, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    List<Index> indexes = new ArrayList<>();

    // Get primary key information
    List<JdbcIndexBean> jdbcIndexBeans = new ArrayList<>();
    try (ResultSet primaryKeys = metaData.getPrimaryKeys(database, databaseName, tableName)) {
      while (primaryKeys.next()) {
        String pkName = primaryKeys.getString("PK_NAME");
        if (pkName == null) {
          // SQL Server always names PK constraints, but guard defensively
          continue;
        }
        jdbcIndexBeans.add(
            new JdbcIndexBean(
                Index.IndexType.PRIMARY_KEY,
                primaryKeys.getString("COLUMN_NAME"),
                pkName,
                primaryKeys.getInt("KEY_SEQ")));
      }
    }

    Set<String> primaryIndexNames =
        jdbcIndexBeans.stream().map(JdbcIndexBean::getName).collect(Collectors.toSet());

    // Get unique key information; use unique=true to only get unique indexes
    try (ResultSet indexInfo =
        metaData.getIndexInfo(database, databaseName, tableName, true, false)) {
      while (indexInfo.next()) {
        // Filter out table statistics rows
        if (indexInfo.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
          continue;
        }
        String indexName = indexInfo.getString("INDEX_NAME");
        if (indexName == null) {
          continue;
        }
        String loadedTableName = indexInfo.getString("TABLE_NAME");
        if (loadedTableName.equals(tableName)
            && !indexInfo.getBoolean("NON_UNIQUE")
            && !primaryIndexNames.contains(indexName)) {
          jdbcIndexBeans.add(
              new JdbcIndexBean(
                  Index.IndexType.UNIQUE_KEY,
                  indexInfo.getString("COLUMN_NAME"),
                  indexName,
                  indexInfo.getInt("ORDINAL_POSITION")));
        }
      }
    }

    // Assemble into Index
    Map<Index.IndexType, List<JdbcIndexBean>> indexBeanGroupByIndexType =
        jdbcIndexBeans.stream().collect(Collectors.groupingBy(JdbcIndexBean::getIndexType));

    for (Map.Entry<Index.IndexType, List<JdbcIndexBean>> entry :
        indexBeanGroupByIndexType.entrySet()) {
      Map<String, List<JdbcIndexBean>> indexBeanGroupByName =
          entry.getValue().stream().collect(Collectors.groupingBy(JdbcIndexBean::getName));
      for (Map.Entry<String, List<JdbcIndexBean>> indexEntry : indexBeanGroupByName.entrySet()) {
        List<String> colNames =
            indexEntry.getValue().stream()
                .sorted(Comparator.comparingInt(JdbcIndexBean::getOrder))
                .map(JdbcIndexBean::getColName)
                .collect(Collectors.toList());
        String[][] colStrArrays = convertIndexFieldNames(colNames);
        if (entry.getKey() == Index.IndexType.PRIMARY_KEY) {
          indexes.add(Indexes.primary(indexEntry.getKey(), colStrArrays, Map.of()));
        } else {
          indexes.add(Indexes.unique(indexEntry.getKey(), colStrArrays, Map.of()));
        }
      }
    }
    return indexes;
  }

  @Override
  protected ResultSet getTable(Connection connection, String schema, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getTables(database, schema, tableName, null);
  }

  @Override
  protected ResultSet getColumns(Connection connection, String schema, String tableName)
      throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getColumns(database, schema, tableName, null);
  }

  @Override
  public Integer calculateDatetimePrecision(String typeName, int columnSize, int scale) {
    String upperTypeName = typeName.toUpperCase();
    switch (upperTypeName) {
      case "TIME":
      case "DATETIME2":
      case "DATETIMEOFFSET":
        return Math.max(scale, 0);
      default:
        return null;
    }
  }

  @Override
  protected String quoteIdentifier(String identifier) {
    return "[" + identifier.replace("]", "]]") + "]";
  }

  private void appendColumnDefinition(JdbcColumn column, StringBuilder sqlBuilder) {
    sqlBuilder.append(typeConverter.fromGravitino(column.dataType())).append(" ");

    if (column.autoIncrement()) {
      if (!Types.allowAutoIncrement(column.dataType())) {
        throw new IllegalArgumentException(
            "Unsupported auto-increment, column: "
                + column.name()
                + ", type: "
                + column.dataType());
      }
      // IDENTITY(seed, increment): auto-increment starting at 1, incrementing by 1
      sqlBuilder.append("IDENTITY(1,1) ");
    }

    if (column.nullable()) {
      sqlBuilder.append("NULL ");
    } else {
      sqlBuilder.append("NOT NULL ");
    }

    appendDefaultValue(column, sqlBuilder);
  }

  private void appendIndexesSql(Index[] indexes, String tableName, StringBuilder sqlBuilder) {
    for (Index index : indexes) {
      String fieldStr = getIndexFieldStr(index.fieldNames());
      sqlBuilder.append(",\n");
      switch (index.type()) {
        case PRIMARY_KEY:
          String pkName = StringUtils.isNotEmpty(index.name()) ? index.name() : "PK_" + tableName;
          sqlBuilder.append("    CONSTRAINT ").append(quoteIdentifier(pkName));
          sqlBuilder.append(" PRIMARY KEY (").append(fieldStr).append(")");
          break;
        case UNIQUE_KEY:
          if (StringUtils.isNotEmpty(index.name())) {
            sqlBuilder.append("    CONSTRAINT ").append(quoteIdentifier(index.name()));
          }
          sqlBuilder.append(" UNIQUE (").append(fieldStr).append(")");
          break;
        default:
          throw new IllegalArgumentException("SQL Server doesn't support index: " + index.type());
      }
    }
  }

  protected static String getIndexFieldStr(String[][] fieldNames) {
    return Arrays.stream(fieldNames)
        .map(
            colNames -> {
              if (colNames.length > 1) {
                throw new IllegalArgumentException(
                    "Index does not support complex fields in SQL Server.");
              }
              return "[" + colNames[0].replace("]", "]]") + "]";
            })
        .collect(Collectors.joining(", "));
  }

  private String updateCommentDefinition(
      TableChange.UpdateComment updateComment, JdbcTable jdbcTable, String schemaName) {
    String newComment = updateComment.getNewComment();
    if (null == StringIdentifier.fromComment(newComment)) {
      if (StringUtils.isNotEmpty(jdbcTable.comment())) {
        StringIdentifier identifier = StringIdentifier.fromComment(jdbcTable.comment());
        if (null != identifier) {
          newComment = StringIdentifier.addToComment(identifier, newComment);
        }
      }
    }
    // Check if extended property exists, then update or add accordingly
    return SqlServerUtils.generateCheckAndSetCommentSql(
        schemaName, jdbcTable.name(), null, newComment);
  }

  private String deleteColumnFieldDefinition(
      TableChange.DeleteColumn deleteColumn, JdbcTable table, String schemaName) {
    if (deleteColumn.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = deleteColumn.fieldName()[0];
    boolean colExists = columnExists(table, col);
    if (!colExists) {
      if (BooleanUtils.isTrue(deleteColumn.getIfExists())) {
        return "";
      } else {
        throw new IllegalArgumentException("Delete column does not exist: " + col);
      }
    }
    return String.format(
        "ALTER TABLE %s.%s DROP COLUMN %s;",
        quoteIdentifier(schemaName), quoteIdentifier(table.name()), quoteIdentifier(col));
  }

  private String updateColumnDefaultValueFieldDefinition(
      TableChange.UpdateColumnDefaultValue change, JdbcTable jdbcTable, String schemaName) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    // Validate column exists (throws NoSuchColumnException if not found)
    getJdbcColumnFromTable(jdbcTable, col);

    // Drop existing default constraint, then add new one.
    // Use schema-qualified OBJECT_ID to avoid cross-schema ambiguity.
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            "DECLARE @constraintName NVARCHAR(256); "
                + "SELECT @constraintName = dc.name FROM sys.default_constraints dc "
                + "JOIN sys.columns c ON dc.parent_object_id = c.object_id "
                + "AND dc.parent_column_id = c.column_id "
                + "WHERE dc.parent_object_id = OBJECT_ID('%s.%s') AND c.name = '%s'; "
                + "IF @constraintName IS NOT NULL "
                + "EXEC('ALTER TABLE %s.%s DROP CONSTRAINT [' + @constraintName + ']');\n",
            SqlServerUtils.escapeQuote(quoteIdentifier(schemaName)),
            SqlServerUtils.escapeQuote(quoteIdentifier(jdbcTable.name())),
            SqlServerUtils.escapeQuote(col),
            quoteIdentifier(schemaName),
            quoteIdentifier(jdbcTable.name())));

    String defaultValue = columnDefaultValueConverter.fromGravitino(change.getNewDefaultValue());
    if (defaultValue != null) {
      sb.append(
          String.format(
              "ALTER TABLE %s.%s ADD DEFAULT %s FOR %s;",
              quoteIdentifier(schemaName),
              quoteIdentifier(jdbcTable.name()),
              defaultValue,
              quoteIdentifier(col)));
    }
    return sb.toString();
  }

  private String updateColumnTypeFieldDefinition(
      TableChange.UpdateColumnType change, JdbcTable jdbcTable, String schemaName) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    JdbcColumn column = getJdbcColumnFromTable(jdbcTable, col);
    String nullability = column.nullable() ? "NULL" : "NOT NULL";
    return String.format(
        "ALTER TABLE %s.%s ALTER COLUMN %s %s %s;",
        quoteIdentifier(schemaName),
        quoteIdentifier(jdbcTable.name()),
        quoteIdentifier(col),
        typeConverter.fromGravitino(change.getNewDataType()),
        nullability);
  }

  private String renameColumnFieldDefinition(
      TableChange.RenameColumn change, String schemaName, String tableName) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    return String.format(
        "EXEC sp_rename '%s.%s.%s', '%s', 'COLUMN';",
        SqlServerUtils.escapeQuote(schemaName),
        SqlServerUtils.escapeQuote(tableName),
        SqlServerUtils.escapeQuote(change.fieldName()[0]),
        SqlServerUtils.escapeQuote(change.getNewName()));
  }

  private List<String> addColumnFieldDefinition(
      TableChange.AddColumn addColumn, JdbcTable lazyLoadTable, String schemaName) {
    if (addColumn.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    List<String> result = new ArrayList<>();
    String col = addColumn.fieldName()[0];

    StringBuilder colDef = new StringBuilder();
    colDef.append(
        String.format(
            "ALTER TABLE %s.%s ADD %s ",
            quoteIdentifier(schemaName),
            quoteIdentifier(lazyLoadTable.name()),
            quoteIdentifier(col)));
    colDef.append(typeConverter.fromGravitino(addColumn.getDataType())).append(" ");

    if (addColumn.isAutoIncrement()) {
      if (!Types.allowAutoIncrement(addColumn.getDataType())) {
        throw new IllegalArgumentException(
            "Unsupported auto-increment, column: "
                + Arrays.toString(addColumn.getFieldName())
                + ", type: "
                + addColumn.getDataType());
      }
      // IDENTITY(seed, increment): auto-increment starting at 1, incrementing by 1
      colDef.append("IDENTITY(1,1) ");
    }

    if (!addColumn.isNullable()) {
      colDef.append("NOT NULL ");
    }

    if (!Column.DEFAULT_VALUE_NOT_SET.equals(addColumn.getDefaultValue())) {
      colDef
          .append("DEFAULT ")
          .append(columnDefaultValueConverter.fromGravitino(addColumn.getDefaultValue()))
          .append(" ");
    }

    if (!(addColumn.getPosition() instanceof TableChange.Default)) {
      throw new IllegalArgumentException(
          "SQL Server does not support column position in Gravitino.");
    }
    result.add(colDef.append(";").toString());

    if (StringUtils.isNotEmpty(addColumn.getComment())) {
      result.add(
          SqlServerUtils.generateAddColumnCommentSql(
                  schemaName, lazyLoadTable.name(), col, addColumn.getComment())
              + ";");
    }
    return result;
  }

  private String updateColumnCommentFieldDefinition(
      TableChange.UpdateColumnComment change, String schemaName, String tableName) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    String newComment = change.getNewComment();
    // Check if extended property exists, then update or add accordingly
    return SqlServerUtils.generateCheckAndSetCommentSql(schemaName, tableName, col, newComment);
  }

  private String updateColumnNullabilityDefinition(
      TableChange.UpdateColumnNullability change, JdbcTable jdbcTable, String schemaName) {
    if (change.fieldName().length > 1) {
      throw new UnsupportedOperationException(SQLSERVER_NOT_SUPPORT_NESTED_COLUMN_MSG);
    }
    String col = change.fieldName()[0];
    JdbcColumn column = getJdbcColumnFromTable(jdbcTable, col);
    String nullability = change.nullable() ? "NULL" : "NOT NULL";
    return String.format(
        "ALTER TABLE %s.%s ALTER COLUMN %s %s %s;",
        quoteIdentifier(schemaName),
        quoteIdentifier(jdbcTable.name()),
        quoteIdentifier(col),
        typeConverter.fromGravitino(column.dataType()),
        nullability);
  }

  private void configureTypeBean(
      JdbcTypeConverter.JdbcTypeBean typeBean,
      String typeName,
      int maxLength,
      int precision,
      int scale) {
    String lowerTypeName = typeName.toLowerCase();
    switch (lowerTypeName) {
      case "decimal":
      case "numeric":
        typeBean.setColumnSize(precision);
        typeBean.setScale(scale);
        break;
      case "char":
      case "binary":
        typeBean.setColumnSize(maxLength);
        break;
      case "varchar":
        typeBean.setColumnSize(maxLength == -1 ? Integer.MAX_VALUE : maxLength);
        break;
      case "nchar":
        typeBean.setColumnSize(maxLength / 2);
        break;
      case "nvarchar":
        typeBean.setColumnSize(maxLength == -1 ? Integer.MAX_VALUE : maxLength / 2);
        break;
      case "varbinary":
        typeBean.setColumnSize(maxLength == -1 ? Integer.MAX_VALUE : maxLength);
        break;
      case "time":
      case "datetime2":
      case "datetimeoffset":
        typeBean.setColumnSize(precision);
        typeBean.setScale(scale);
        typeBean.setDatetimePrecision(scale);
        break;
      default:
        typeBean.setColumnSize(precision);
        typeBean.setScale(scale);
        break;
    }
  }

  private String getSchemaName() {
    return currentSchemaName != null ? currentSchemaName : "dbo";
  }
}
