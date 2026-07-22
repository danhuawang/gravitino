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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.oracle.converter.OracleColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.oracle.converter.OracleExceptionConverter;
import com.datastrato.gravitino.catalog.oracle.converter.OracleTypeConverter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.partitions.ListPartition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.partitions.RangePartition;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestOracleTableOperations {

  private OracleTableOperationsForTest operations;
  private DataSource dataSource;
  private Connection connection;

  private static final class OracleTableOperationsForTest extends OracleTableOperations {
    JdbcTable tableForLoad;

    @Override
    public JdbcTable load(String databaseName, String tableName) {
      if (tableForLoad != null) {
        return tableForLoad;
      }
      return super.load(databaseName, tableName);
    }

    String createSqlForTest(
        String tableName, JdbcColumn[] columns, Map<String, String> properties, Index[] indexes) {
      return super.generateCreateTableSql(
          tableName,
          columns,
          "table comment",
          properties,
          Transforms.EMPTY_TRANSFORM,
          Distributions.NONE,
          indexes);
    }

    String createSqlWithPartitionForTest(
        String tableName,
        JdbcColumn[] columns,
        Map<String, String> properties,
        Transform[] partitioning) {
      return super.generateCreateTableSql(
          tableName,
          columns,
          "table comment",
          properties,
          partitioning,
          Distributions.NONE,
          new Index[0]);
    }

    String dropSqlForTest(String tableName) {
      return super.generateDropTableSql(tableName);
    }

    boolean getAutoIncrementInfoForTest(ResultSet resultSet) throws SQLException {
      return super.getAutoIncrementInfo(resultSet);
    }

    Map<String, String> getTablePropertiesForTest(Connection conn, String tableName)
        throws SQLException {
      return super.getTableProperties(conn, tableName);
    }

    List<Index> getIndexesForTest(Connection conn, String databaseName, String tableName)
        throws SQLException {
      return super.getIndexes(conn, databaseName, tableName);
    }

    Transform[] getTablePartitioningForTest(Connection conn, String databaseName, String tableName)
        throws SQLException {
      return super.getTablePartitioning(conn, databaseName, tableName);
    }

    void correctJdbcTableFieldsForTest(
        Connection conn, String databaseName, String tableName, JdbcTable.Builder builder)
        throws SQLException {
      super.correctJdbcTableFields(conn, databaseName, tableName, builder);
    }

    ResultSet getTablesForTest(Connection conn) throws SQLException {
      return super.getTables(conn);
    }

    ResultSet getTableForTest(Connection conn, String databaseName, String tableName)
        throws SQLException {
      return super.getTable(conn, databaseName, tableName);
    }

    ResultSet getColumnsForTest(Connection conn, String databaseName, String tableName)
        throws SQLException {
      return super.getColumns(conn, databaseName, tableName);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    operations = new OracleTableOperationsForTest();
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    operations.initialize(
        dataSource,
        new OracleExceptionConverter(),
        new OracleTypeConverter(),
        new OracleColumnDefaultValueConverter(),
        Collections.emptyMap());
  }

  @Test
  void testCreateAndDropSql() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.IntegerType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("name")
              .withType(Types.VarCharType.of(16))
              .withNullable(true)
              .build()
        };

    String sql =
        operations.createSqlForTest(
            "t1",
            columns,
            Map.of(TABLESPACE, "USERS"),
            new Index[] {
              Indexes.primary("PK_T1", new String[][] {new String[] {"id"}}),
              Indexes.unique("UK_T1_NAME", new String[][] {new String[] {"name"}})
            });
    assertTrue(sql.contains("CREATE TABLE \"t1\""));
    assertTrue(sql.contains("CONSTRAINT \"PK_T1\" PRIMARY KEY (\"id\")"));
    assertTrue(sql.contains("CONSTRAINT \"UK_T1_NAME\" UNIQUE (\"name\")"));
    assertTrue(sql.endsWith("TABLESPACE \"USERS\""));

    assertEquals("DROP TABLE \"t1\" PURGE", operations.dropSqlForTest("t1"));
  }

  @Test
  void testCreateSqlWithUnnamedConstraintsAndEscapedIdentifier() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder().withName("id").withType(Types.IntegerType.get()).build(),
          JdbcColumn.builder().withName("name").withType(Types.VarCharType.of(16)).build()
        };

    String sql =
        operations.createSqlForTest(
            "a\"b",
            columns,
            Collections.emptyMap(),
            new Index[] {
              Indexes.primary("", new String[][] {new String[] {"id"}}),
              Indexes.unique("", new String[][] {new String[] {"name"}})
            });
    assertTrue(sql.contains("CREATE TABLE \"a\"\"b\""));
    assertTrue(sql.contains("PRIMARY KEY (\"id\")"));
    assertTrue(sql.contains("UNIQUE (\"name\")"));
    assertFalse(sql.contains("CONSTRAINT"));
  }

  @Test
  void testGetTablePropertiesAndIndexes() throws Exception {
    PreparedStatement propertiesStmt = mock(PreparedStatement.class);
    ResultSet propertiesRs = mock(ResultSet.class);
    PreparedStatement indexStmt = mock(PreparedStatement.class);
    ResultSet indexRs = mock(ResultSet.class);

    when(connection.prepareStatement(anyString())).thenReturn(propertiesStmt, indexStmt);
    when(connection.getSchema()).thenReturn("APP_USER");
    when(propertiesStmt.executeQuery()).thenReturn(propertiesRs);
    when(propertiesRs.next()).thenReturn(true);
    when(propertiesRs.getString("TABLESPACE_NAME")).thenReturn("USERS");
    when(propertiesRs.getString("PARTITIONED")).thenReturn("NO");
    when(propertiesRs.getString("ROW_MOVEMENT")).thenReturn("DISABLED");
    when(propertiesRs.getString("COMPRESSION")).thenReturn("DISABLED");
    when(propertiesRs.getString("COMMENTS")).thenReturn("table comment");

    Map<String, String> properties = operations.getTablePropertiesForTest(connection, "mixedCase");
    assertEquals("USERS", properties.get(TABLESPACE));
    assertEquals("NO", properties.get(PARTITIONED));
    assertEquals("DISABLED", properties.get(ROW_MOVEMENT));
    assertEquals("DISABLED", properties.get(COMPRESSION));
    assertEquals("table comment", properties.get(COMMENT_KEY));
    verify(propertiesStmt).setString(1, "APP_USER");
    verify(propertiesStmt).setString(2, "mixedCase");

    JdbcTable.Builder builder = JdbcTable.builder().withComment("").withProperties(properties);
    operations.correctJdbcTableFieldsForTest(connection, "APP_USER", "T1", builder);
    assertEquals("table comment", builder.comment());

    when(indexStmt.executeQuery()).thenReturn(indexRs);
    when(indexRs.next()).thenReturn(true, true, true, false);
    when(indexRs.getString("CONSTRAINT_NAME")).thenReturn("PK_T1", "PK_T1", "UK_T1_NAME");
    when(indexRs.getString("CONSTRAINT_TYPE")).thenReturn("P", "P", "U");
    when(indexRs.getString("COLUMN_NAME")).thenReturn("ID", "ID2", "NAME");

    List<Index> indexes = operations.getIndexesForTest(connection, "APP_USER", "T1");
    assertEquals(2, indexes.size());
    assertEquals(Index.IndexType.PRIMARY_KEY, indexes.get(0).type());
    assertEquals(Index.IndexType.UNIQUE_KEY, indexes.get(1).type());

    verify(indexStmt).setString(1, "APP_USER");
    verify(indexStmt).setString(2, "T1");
  }

  @Test
  void testCorrectJdbcTableFieldsPopulatesColumnComments() throws Exception {
    // Simulate the Oracle driver leaving REMARKS empty: columns loaded with null comments.
    JdbcColumn idColumn =
        JdbcColumn.builder()
            .withName("ID")
            .withType(Types.IntegerType.get())
            .withNullable(false)
            .build();
    JdbcColumn nameColumn =
        JdbcColumn.builder()
            .withName("NAME")
            .withType(Types.VarCharType.of(64))
            .withNullable(true)
            .build();

    PreparedStatement commentStmt = mock(PreparedStatement.class);
    ResultSet commentRs = mock(ResultSet.class);
    when(connection.getSchema()).thenReturn("APP_USER");
    when(connection.prepareStatement(anyString())).thenReturn(commentStmt);
    when(commentStmt.executeQuery()).thenReturn(commentRs);
    // Only NAME has a comment in ALL_COL_COMMENTS; ID has none.
    when(commentRs.next()).thenReturn(true, true, false);
    when(commentRs.getString("COLUMN_NAME")).thenReturn("NAME", "ID");
    when(commentRs.getString("COMMENTS")).thenReturn("the person name", (String) null);

    JdbcTable.Builder builder =
        JdbcTable.builder()
            .withName("T1")
            .withComment("")
            .withProperties(Map.of())
            .withColumns(new JdbcColumn[] {idColumn, nameColumn});
    operations.correctJdbcTableFieldsForTest(connection, "APP_USER", "T1", builder);

    Map<String, String> commentByName = new HashMap<>();
    for (Column column : builder.columns()) {
      commentByName.put(column.name(), column.comment());
    }
    assertEquals("the person name", commentByName.get("NAME"));
    assertEquals(null, commentByName.get("ID"));

    verify(commentStmt).setString(1, "APP_USER");
    verify(commentStmt).setString(2, "T1");
  }

  @Test
  void testCorrectJdbcTableFieldsSkipsColumnCommentQueryWhenNoColumns() throws Exception {
    // Builder without columns must not issue an ALL_COL_COMMENTS query.
    JdbcTable.Builder builder =
        JdbcTable.builder().withName("T1").withComment("").withProperties(Map.of());
    operations.correctJdbcTableFieldsForTest(connection, "APP_USER", "T1", builder);
    verify(connection, never()).prepareStatement(anyString());
  }

  @Test
  void testCreateTableWithPartitioning() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.IntegerType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("region")
              .withType(Types.VarCharType.of(32))
              .withNullable(true)
              .build()
        };
    Map<String, String> props = Map.of();

    // HASH partitioning
    String hashSql =
        operations.createSqlWithPartitionForTest(
            "T1", columns, props, new Transform[] {Transforms.bucket(4, new String[] {"id"})});
    assertTrue(hashSql.contains("PARTITION BY HASH (\"id\") PARTITIONS 4"));

    // RANGE partitioning without assignments
    String rangeSql =
        operations.createSqlWithPartitionForTest(
            "T1", columns, props, new Transform[] {Transforms.range(new String[] {"id"})});
    assertTrue(rangeSql.contains("PARTITION BY RANGE (\"id\")"));
    assertFalse(rangeSql.contains("VALUES LESS THAN"));

    // RANGE partitioning with assignments
    String rangeWithPartsSql =
        operations.createSqlWithPartitionForTest(
            "T1",
            columns,
            props,
            new Transform[] {
              Transforms.range(
                  new String[] {"id"},
                  new RangePartition[] {
                    Partitions.range("p1", Literals.longLiteral(100L), Literals.NULL, Map.of()),
                    Partitions.range(
                        "p_date", Literals.dateLiteral("2026-04-27"), Literals.NULL, Map.of()),
                    Partitions.range(
                        "p\"quote", Literals.longLiteral(200L), Literals.NULL, Map.of()),
                    Partitions.range("p2", Literals.NULL, Literals.NULL, Map.of())
                  })
            });
    assertTrue(rangeWithPartsSql.contains("PARTITION BY RANGE (\"id\")"));
    assertTrue(rangeWithPartsSql.contains("PARTITION \"p1\" VALUES LESS THAN (100)"));
    assertTrue(rangeWithPartsSql.contains("PARTITION \"p_date\" VALUES LESS THAN ('2026-04-27')"));
    assertTrue(rangeWithPartsSql.contains("PARTITION \"p\"\"quote\" VALUES LESS THAN (200)"));
    assertTrue(rangeWithPartsSql.contains("PARTITION \"p2\" VALUES LESS THAN (MAXVALUE)"));

    // LIST partitioning without assignments
    String listSql =
        operations.createSqlWithPartitionForTest(
            "T1",
            columns,
            props,
            new Transform[] {Transforms.list(new String[][] {new String[] {"region"}})});
    assertTrue(listSql.contains("PARTITION BY LIST (\"region\")"));
    assertFalse(listSql.contains("VALUES"));

    // LIST partitioning with assignments
    String listWithPartsSql =
        operations.createSqlWithPartitionForTest(
            "T1",
            columns,
            props,
            new Transform[] {
              Transforms.list(
                  new String[][] {new String[] {"region"}},
                  new ListPartition[] {
                    Partitions.list(
                        "p_east",
                        new Literal<?>[][] {new Literal<?>[] {Literals.stringLiteral("East")}},
                        Map.of()),
                    Partitions.list(
                        "p_west",
                        new Literal<?>[][] {new Literal<?>[] {Literals.stringLiteral("West")}},
                        Map.of())
                  })
            });
    assertTrue(listWithPartsSql.contains("PARTITION BY LIST (\"region\")"));
    assertTrue(listWithPartsSql.contains("PARTITION \"p_east\" VALUES ('East')"));
    assertTrue(listWithPartsSql.contains("PARTITION \"p_west\" VALUES ('West')"));
  }

  @Test
  void testCreateTableWithUnsupportedPartitioning() {
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder().withName("id").withType(Types.IntegerType.get()).build(),
          JdbcColumn.builder().withName("region").withType(Types.VarCharType.of(32)).build()
        };

    assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.createSqlWithPartitionForTest(
                "T1",
                columns,
                Map.of(),
                new Transform[] {
                  Transforms.range(
                      new String[] {"id"},
                      new RangePartition[] {
                        Partitions.range(
                            "p1", Literals.longLiteral(100L), Literals.longLiteral(1L), Map.of())
                      })
                }));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.createSqlWithPartitionForTest(
                "T1",
                columns,
                Map.of(),
                new Transform[] {
                  Transforms.list(new String[][] {new String[] {"nested", "field"}})
                }));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.createSqlWithPartitionForTest(
                "T1",
                columns,
                Map.of(),
                new Transform[] {Transforms.bucket(4, new String[] {"nested", "field"})}));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.createSqlWithPartitionForTest(
                "T1",
                columns,
                Map.of(),
                new Transform[] {
                  Transforms.list(
                      new String[][] {new String[] {"id"}, new String[] {"region"}},
                      new ListPartition[] {
                        Partitions.list(
                            "p1",
                            new Literal<?>[][] {new Literal<?>[] {Literals.integerLiteral(1)}},
                            Map.of())
                      })
                }));
  }

  @Test
  void testGetTablePartitioning() throws Exception {
    PreparedStatement typeStmt = mock(PreparedStatement.class);
    ResultSet typeRs = mock(ResultSet.class);
    PreparedStatement colStmt = mock(PreparedStatement.class);
    ResultSet colRs = mock(ResultSet.class);

    when(connection.prepareStatement(anyString())).thenReturn(typeStmt, colStmt);
    when(typeStmt.executeQuery()).thenReturn(typeRs);
    when(typeRs.next()).thenReturn(true);
    when(typeRs.getString("PARTITIONING_TYPE")).thenReturn("HASH");
    when(typeRs.getInt("PARTITION_COUNT")).thenReturn(4);
    when(colStmt.executeQuery()).thenReturn(colRs);
    when(colRs.next()).thenReturn(true, false);
    when(colRs.getString("COLUMN_NAME")).thenReturn("ID");

    Transform[] transforms = operations.getTablePartitioningForTest(connection, "APP_USER", "T1");
    assertEquals(1, transforms.length);
    assertTrue(transforms[0] instanceof Transforms.BucketTransform);
    assertEquals(4, ((Transforms.BucketTransform) transforms[0]).numBuckets());
    verify(typeStmt).setString(1, "APP_USER");
    verify(typeStmt).setString(2, "T1");
    verify(colStmt).setString(1, "APP_USER");
    verify(colStmt).setString(2, "T1");

    // RANGE: reset mocks
    PreparedStatement typeStmt2 = mock(PreparedStatement.class);
    ResultSet typeRs2 = mock(ResultSet.class);
    PreparedStatement colStmt2 = mock(PreparedStatement.class);
    ResultSet colRs2 = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(typeStmt2, colStmt2);
    when(typeStmt2.executeQuery()).thenReturn(typeRs2);
    when(typeRs2.next()).thenReturn(true);
    when(typeRs2.getString("PARTITIONING_TYPE")).thenReturn("RANGE");
    when(typeRs2.getInt("PARTITION_COUNT")).thenReturn(2);
    when(colStmt2.executeQuery()).thenReturn(colRs2);
    when(colRs2.next()).thenReturn(true, false);
    when(colRs2.getString("COLUMN_NAME")).thenReturn("ORDER_DATE");

    Transform[] rangeTransforms =
        operations.getTablePartitioningForTest(connection, "APP_USER", "T1");
    assertEquals(1, rangeTransforms.length);
    assertTrue(rangeTransforms[0] instanceof Transforms.RangeTransform);
    assertEquals("ORDER_DATE", ((Transforms.RangeTransform) rangeTransforms[0]).fieldName()[0]);

    PreparedStatement typeStmt3 = mock(PreparedStatement.class);
    ResultSet typeRs3 = mock(ResultSet.class);
    PreparedStatement colStmt3 = mock(PreparedStatement.class);
    ResultSet colRs3 = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(typeStmt3, colStmt3);
    when(typeStmt3.executeQuery()).thenReturn(typeRs3);
    when(typeRs3.next()).thenReturn(true);
    when(typeRs3.getString("PARTITIONING_TYPE")).thenReturn("LIST");
    when(colStmt3.executeQuery()).thenReturn(colRs3);
    when(colRs3.next()).thenReturn(true, true, false);
    when(colRs3.getString("COLUMN_NAME")).thenReturn("REGION", "CATEGORY");

    Transform[] listTransforms =
        operations.getTablePartitioningForTest(connection, "APP_USER", "MixedTable");
    assertEquals(1, listTransforms.length);
    assertTrue(listTransforms[0] instanceof Transforms.ListTransform);
    assertEquals("REGION", ((Transforms.ListTransform) listTransforms[0]).fieldNames()[0][0]);
    assertEquals("CATEGORY", ((Transforms.ListTransform) listTransforms[0]).fieldNames()[1][0]);
    verify(typeStmt3).setString(2, "MixedTable");
    verify(colStmt3).setString(2, "MixedTable");

    PreparedStatement typeStmt4 = mock(PreparedStatement.class);
    ResultSet typeRs4 = mock(ResultSet.class);
    PreparedStatement colStmt4 = mock(PreparedStatement.class);
    ResultSet colRs4 = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(typeStmt4, colStmt4);
    when(typeStmt4.executeQuery()).thenReturn(typeRs4);
    when(typeRs4.next()).thenReturn(true);
    when(typeRs4.getString("PARTITIONING_TYPE")).thenReturn("RANGE");
    when(colStmt4.executeQuery()).thenReturn(colRs4);
    when(colRs4.next()).thenReturn(true, true, false);
    when(colRs4.getString("COLUMN_NAME")).thenReturn("C1", "C2");

    assertThrows(
        UnsupportedOperationException.class,
        () -> operations.getTablePartitioningForTest(connection, "APP_USER", "T1"));

    PreparedStatement typeStmt5 = mock(PreparedStatement.class);
    ResultSet typeRs5 = mock(ResultSet.class);
    PreparedStatement colStmt5 = mock(PreparedStatement.class);
    ResultSet colRs5 = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(typeStmt5, colStmt5);
    when(typeStmt5.executeQuery()).thenReturn(typeRs5);
    when(typeRs5.next()).thenReturn(true);
    when(typeRs5.getString("PARTITIONING_TYPE")).thenReturn("INTERVAL");
    when(colStmt5.executeQuery()).thenReturn(colRs5);
    when(colRs5.next()).thenReturn(true, false);
    when(colRs5.getString("COLUMN_NAME")).thenReturn("ORDER_DATE");

    assertEquals(0, operations.getTablePartitioningForTest(connection, "APP_USER", "T1").length);
  }

  @Test
  void testGetTablePropertiesThrowsWhenTableMissing() throws Exception {
    PreparedStatement propertiesStmt = mock(PreparedStatement.class);
    ResultSet propertiesRs = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(propertiesStmt);
    when(connection.getSchema()).thenReturn("APP_USER");
    when(propertiesStmt.executeQuery()).thenReturn(propertiesRs);
    when(propertiesRs.next()).thenReturn(false);

    assertThrows(
        NoSuchTableException.class, () -> operations.getTablePropertiesForTest(connection, "T1"));
  }

  @Test
  void testMetadataAndUnsupportedPaths() throws Exception {
    Statement statement = mock(Statement.class);
    PreparedStatement partitionTypeStmt = mock(PreparedStatement.class);
    ResultSet partitionTypeRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.prepareStatement(anyString())).thenReturn(partitionTypeStmt);
    when(partitionTypeStmt.executeQuery()).thenReturn(partitionTypeRs);
    when(partitionTypeRs.next()).thenReturn(false);
    when(connection.getSchema()).thenReturn("APP_USER");
    Connection schemaConnection = operations.getConnection("app_user");
    assertEquals(connection, schemaConnection);
    verify(statement).execute(eq("ALTER SESSION SET CURRENT_SCHEMA = \"APP_USER\""));
    verify(connection).setSchema("APP_USER");

    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet tables = mock(ResultSet.class);
    ResultSet table = mock(ResultSet.class);
    ResultSet columns = mock(ResultSet.class);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getTables(null, "APP_USER", null, new String[] {"TABLE"})).thenReturn(tables);
    when(metaData.getTables(null, "APP_USER", "T1", new String[] {"TABLE"})).thenReturn(table);
    when(metaData.getColumns(null, "APP_USER", "T1", null)).thenReturn(columns);

    assertEquals(tables, operations.getTablesForTest(connection));
    assertEquals(table, operations.getTableForTest(connection, "APP_USER", "T1"));
    assertEquals(columns, operations.getColumnsForTest(connection, "APP_USER", "T1"));

    assertFalse(operations.getAutoIncrementInfoForTest(null));
    assertEquals(0, operations.getTablePartitioningForTest(connection, "APP_USER", "T1").length);

    assertEquals(
        "ALTER TABLE \"T1\" RENAME TO \"T2\"", operations.generateRenameTableSql("T1", "T2"));
    assertEquals("", operations.generateAlterTableSql("APP_USER", "T1", new TableChange[0]));
  }

  @Test
  void testGenerateAlterTableSql() {
    String sql =
        operations.generateAlterTableSql(
            "APP_USER",
            "T1",
            TableChange.addColumn(
                new String[] {"new_col"},
                Types.VarCharType.of(100),
                "new col comment",
                TableChange.ColumnPosition.defaultPos(),
                true,
                false,
                Literals.stringLiteral("v1")),
            TableChange.updateColumnType(new String[] {"new_col"}, Types.LongType.get()),
            TableChange.updateColumnDefaultValue(
                new String[] {"new_col"}, Literals.longLiteral(10L)),
            TableChange.updateColumnNullability(new String[] {"new_col"}, false),
            TableChange.renameColumn(new String[] {"new_col"}, "new_col_2"),
            TableChange.updateColumnComment(new String[] {"new_col_2"}, "updated"),
            TableChange.deleteColumn(new String[] {"old_col"}, false),
            TableChange.updateComment("table comment"));

    assertTrue(
        sql.contains(
            "ALTER TABLE \"APP_USER\".\"T1\" ADD (\"new_col\" VARCHAR2(100) DEFAULT 'v1')"));
    assertTrue(
        sql.contains("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"new_col\" IS 'new col comment'"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" MODIFY (\"new_col\" NUMBER(19))"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" MODIFY (\"new_col\" DEFAULT 10)"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" MODIFY (\"new_col\" NOT NULL)"));
    assertTrue(
        sql.contains("ALTER TABLE \"APP_USER\".\"T1\" RENAME COLUMN \"new_col\" TO \"new_col_2\""));
    assertTrue(sql.contains("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"new_col_2\" IS 'updated'"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" DROP COLUMN \"old_col\""));
    assertTrue(sql.contains("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'"));
  }

  @Test
  void testGenerateAlterTableSqlNormalizesSchemaName() {
    String sql =
        operations.generateAlterTableSql(
            "app_user", "T1", TableChange.updateComment("table comment"));

    assertEquals("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'", sql);
  }

  @Test
  void testGenerateAlterTableSqlSupportsNullColumnComment() {
    String sql =
        operations.generateAlterTableSql(
            "app_user", "T1", TableChange.updateColumnComment(new String[] {"col_a"}, null));

    assertEquals("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"col_a\" IS NULL", sql);
  }

  @Test
  void testAlterTableExecutesGeneratedSqls() throws Exception {
    Statement sessionStatement = mock(Statement.class);
    Statement ddlStatement1 = mock(Statement.class);
    Statement ddlStatement2 = mock(Statement.class);
    when(connection.createStatement()).thenReturn(sessionStatement, ddlStatement1, ddlStatement2);

    operations.alterTable(
        "app_user",
        "T1",
        TableChange.updateComment("table comment"),
        TableChange.updateColumnComment(new String[] {"col_a"}, "column comment"));

    verify(sessionStatement).execute(eq("ALTER SESSION SET CURRENT_SCHEMA = \"APP_USER\""));
    verify(connection).setSchema("APP_USER");
    verify(ddlStatement1)
        .executeUpdate(eq("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'"));
    verify(ddlStatement2)
        .executeUpdate(eq("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"col_a\" IS 'column comment'"));
  }

  @Test
  void testAlterTableThrowsWhenExecutingGeneratedSqlFails() throws Exception {
    Statement sessionStatement = mock(Statement.class);
    Statement ddlStatement1 = mock(Statement.class);
    Statement ddlStatement2 = mock(Statement.class);
    when(connection.createStatement()).thenReturn(sessionStatement, ddlStatement1, ddlStatement2);
    when(ddlStatement2.executeUpdate(
            eq("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"col_a\" IS 'column comment'")))
        .thenThrow(new SQLException("failed"));

    assertThrows(
        GravitinoRuntimeException.class,
        () ->
            operations.alterTable(
                "app_user",
                "T1",
                TableChange.updateComment("table comment"),
                TableChange.updateColumnComment(new String[] {"col_a"}, "column comment")));

    verify(ddlStatement1)
        .executeUpdate(eq("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'"));
    verify(ddlStatement2)
        .executeUpdate(eq("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"col_a\" IS 'column comment'"));
  }

  @Test
  void testAlterTableSkipsExecutionWhenNoChanges() throws Exception {
    operations.alterTable("APP_USER", "T1", new TableChange[0]);

    verify(dataSource, never()).getConnection();
  }

  @Test
  void testGenerateAlterTableSqlIndexChanges() {
    String addPkSql =
        operations.generateAlterTableSql(
            "APP_USER",
            "T1",
            TableChange.addIndex(
                Index.IndexType.PRIMARY_KEY, "PK_T1", new String[][] {new String[] {"id"}}));
    assertTrue(
        addPkSql.contains(
            "ALTER TABLE \"APP_USER\".\"T1\" ADD CONSTRAINT \"PK_T1\" PRIMARY KEY (\"id\")"));

    String addUkSql =
        operations.generateAlterTableSql(
            "APP_USER",
            "T1",
            TableChange.addIndex(
                Index.IndexType.UNIQUE_KEY, "UK_T1_NAME", new String[][] {new String[] {"name"}}));
    assertTrue(
        addUkSql.contains(
            "ALTER TABLE \"APP_USER\".\"T1\" ADD CONSTRAINT \"UK_T1_NAME\" UNIQUE (\"name\")"));

    String dropSql =
        operations.generateAlterTableSql("APP_USER", "T1", TableChange.deleteIndex("PK_T1", false));
    assertTrue(dropSql.contains("ALTER TABLE \"APP_USER\".\"T1\" DROP CONSTRAINT \"PK_T1\""));

    operations.tableForLoad =
        JdbcTable.builder()
            .withName("T1")
            .withDatabaseName("APP_USER")
            .withColumns(new JdbcColumn[0])
            .withIndexes(new Index[] {Indexes.primary("PK_T1", new String[][] {{"id"}})})
            .build();

    String skipMissingIndexSql =
        operations.generateAlterTableSql(
            "APP_USER", "T1", TableChange.deleteIndex("MISSING_INDEX", true));
    assertEquals("", skipMissingIndexSql);

    String dropExistingIndexSql =
        operations.generateAlterTableSql("APP_USER", "T1", TableChange.deleteIndex("PK_T1", true));
    assertTrue(
        dropExistingIndexSql.contains("ALTER TABLE \"APP_USER\".\"T1\" DROP CONSTRAINT \"PK_T1\""));
  }

  @Test
  void testGenerateAlterTableSqlUnsupportedOperations() {
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            operations.generateAlterTableSql(
                "APP_USER",
                "T1",
                TableChange.updateColumnPosition(
                    new String[] {"id"}, TableChange.ColumnPosition.first())));

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            operations.generateAlterTableSql(
                "APP_USER",
                "T1",
                TableChange.updateColumnAutoIncrement(new String[] {"id"}, true)));

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            operations.generateAlterTableSql("APP_USER", "T1", TableChange.setProperty("k", "v")));
  }

  @Test
  void testGetConnectionClosesOnSessionInitFailure() throws Exception {
    Statement statement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenThrow(new SQLException("failed"));

    assertThrows(SQLException.class, () -> operations.getConnection("app_user"));
    verify(connection).close();
  }
}
