/*
 * Copyright 2026 Datastrato Inc.
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.oracle.OracleTablePropertiesMetadata;
import com.datastrato.gravitino.catalog.oracle.converter.OracleColumnDefaultValueConverter;
import com.datastrato.gravitino.catalog.oracle.converter.OracleExceptionConverter;
import com.datastrato.gravitino.catalog.oracle.converter.OracleTypeConverter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchColumnException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableChange;
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
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseMajorVersion()).thenReturn(23);
    operations.initialize(
        dataSource,
        new OracleExceptionConverter(),
        new OracleTypeConverter(),
        new OracleColumnDefaultValueConverter(),
        Collections.emptyMap());
    clearInvocations(dataSource, connection, metaData);
  }

  @Test
  void testCreateAndDropSql() {
    // Names have already been normalized to their physical (uppercase) form by
    // OracleCatalogCapability.normalizeName before reaching generateCreateTableSql, which only
    // quotes them.
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("ID")
              .withType(Types.IntegerType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("NAME")
              .withType(Types.VarCharType.of(16))
              .withNullable(true)
              .build()
        };

    String sql =
        operations.createSqlForTest(
            "T1",
            columns,
            Map.of(TABLESPACE, "USERS"),
            new Index[] {
              Indexes.primary("PK_T1", new String[][] {new String[] {"ID"}}),
              Indexes.unique("UK_T1_NAME", new String[][] {new String[] {"NAME"}})
            });
    assertTrue(sql.contains("CREATE TABLE \"T1\""));
    assertTrue(sql.contains("CONSTRAINT \"PK_T1\" PRIMARY KEY (\"ID\")"));
    assertTrue(sql.contains("CONSTRAINT \"UK_T1_NAME\" UNIQUE (\"NAME\")"));
    assertTrue(sql.endsWith("TABLESPACE \"USERS\""));

    assertEquals("DROP TABLE \"T1\" PURGE", operations.dropSqlForTest("T1"));
  }

  @Test
  void testLegacyBooleanIntentRoundTrip() throws Exception {
    DataSource legacyDataSource = mock(DataSource.class);
    Connection legacyConnection = mock(Connection.class);
    DatabaseMetaData legacyMetaData = mock(DatabaseMetaData.class);
    when(legacyDataSource.getConnection()).thenReturn(legacyConnection);
    when(legacyConnection.getMetaData()).thenReturn(legacyMetaData);
    when(legacyMetaData.getDatabaseMajorVersion()).thenReturn(11);

    OracleTableOperationsForTest legacyOperations = new OracleTableOperationsForTest();
    legacyOperations.initialize(
        legacyDataSource,
        new OracleExceptionConverter(),
        new OracleTypeConverter(),
        new OracleColumnDefaultValueConverter(),
        Collections.emptyMap());
    clearInvocations(legacyDataSource, legacyConnection, legacyMetaData);

    Statement sessionStatement = mock(Statement.class);
    Statement createStatement = mock(Statement.class);
    Statement commentStatement = mock(Statement.class);
    when(legacyConnection.createStatement())
        .thenReturn(sessionStatement, createStatement, commentStatement);
    JdbcColumn flag =
        JdbcColumn.builder()
            .withName("FLAG")
            .withType(Types.BooleanType.get())
            .withComment("active flag")
            .withNullable(true)
            .build();

    legacyOperations.create(
        "APP_USER",
        "T1",
        new JdbcColumn[] {flag},
        null,
        Map.of(),
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        Indexes.EMPTY_INDEXES,
        new SortOrder[0]);

    verify(createStatement).executeUpdate(eq("CREATE TABLE \"T1\" (\"FLAG\" NUMBER(1))"));
    verify(commentStatement)
        .executeUpdate(
            eq("COMMENT ON COLUMN \"T1\".\"FLAG\" IS " + "'__GRAVITINO_BOOLEAN__\nactive flag'"));

    PreparedStatement commentQuery = mock(PreparedStatement.class);
    ResultSet commentResult = mock(ResultSet.class);
    when(legacyConnection.getSchema()).thenReturn("APP_USER");
    when(legacyConnection.prepareStatement(anyString())).thenReturn(commentQuery);
    when(commentQuery.executeQuery()).thenReturn(commentResult);
    when(commentResult.next()).thenReturn(true, true, false);
    when(commentResult.getString("COLUMN_NAME")).thenReturn("FLAG", "SOURCE_FLAG");
    when(commentResult.getString("COMMENTS"))
        .thenReturn("__GRAVITINO_BOOLEAN__\nactive flag", "source number flag");

    JdbcTable.Builder builder =
        JdbcTable.builder()
            .withName("T1")
            .withComment("")
            .withProperties(Map.of())
            .withColumns(
                new JdbcColumn[] {
                  JdbcColumn.builder()
                      .withName("FLAG")
                      .withType(Types.ByteType.get())
                      .withNullable(true)
                      .build(),
                  JdbcColumn.builder()
                      .withName("SOURCE_FLAG")
                      .withType(Types.ByteType.get())
                      .withNullable(true)
                      .build()
                });
    legacyOperations.correctJdbcTableFieldsForTest(legacyConnection, "APP_USER", "T1", builder);

    assertEquals(Types.BooleanType.get(), builder.columns()[0].dataType());
    assertEquals("active flag", builder.columns()[0].comment());
    assertEquals(Types.ByteType.get(), builder.columns()[1].dataType());
    assertEquals("source number flag", builder.columns()[1].comment());
  }

  @Test
  void testCreateSqlWithUnnamedConstraintsAndReservedWordColumns() {
    // Unnamed constraints and reserved-word column names (e.g. "comment", "number") are safe
    // because quote() wraps the name in double quotes.
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder().withName("ID").withType(Types.IntegerType.get()).build(),
          JdbcColumn.builder().withName("COMMENT").withType(Types.VarCharType.of(255)).build()
        };

    String sql =
        operations.createSqlForTest(
            "T1",
            columns,
            Collections.emptyMap(),
            new Index[] {
              Indexes.primary("", new String[][] {new String[] {"ID"}}),
              Indexes.unique("", new String[][] {new String[] {"COMMENT"}})
            });
    assertTrue(sql.contains("CREATE TABLE \"T1\""));
    assertTrue(sql.contains("\"COMMENT\" VARCHAR2(255)"));
    assertTrue(sql.contains("PRIMARY KEY (\"ID\")"));
    assertTrue(sql.contains("UNIQUE (\"COMMENT\")"));
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

    Map<String, String> properties = operations.getTablePropertiesForTest(connection, "MixedCase");
    assertEquals("USERS", properties.get(TABLESPACE));
    assertEquals("NO", properties.get(PARTITIONED));
    assertEquals("DISABLED", properties.get(ROW_MOVEMENT));
    assertEquals("DISABLED", properties.get(COMPRESSION));
    assertEquals("table comment", properties.get(COMMENT_KEY));
    verify(propertiesStmt).setString(1, "APP_USER");
    // tableName has already been normalized by OracleCatalogCapability.normalizeName, so it is
    // bound as-is, with its case preserved, not re-folded.
    verify(propertiesStmt).setString(2, "MixedCase");

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
    // Constraint/column names are returned exactly as read from the JDBC result set.
    assertEquals("PK_T1", indexes.get(0).name());
    assertEquals("ID", indexes.get(0).fieldNames()[0][0]);
    assertEquals("ID2", indexes.get(0).fieldNames()[1][0]);
    assertEquals(Index.IndexType.UNIQUE_KEY, indexes.get(1).type());
    assertEquals("UK_T1_NAME", indexes.get(1).name());
    assertEquals("NAME", indexes.get(1).fieldNames()[0][0]);

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
    // Column, partition, and table names have already been normalized to their physical
    // (uppercase) form by core before reaching this method, which only quotes them.
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("ID")
              .withType(Types.IntegerType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("REGION")
              .withType(Types.VarCharType.of(32))
              .withNullable(true)
              .build()
        };
    Map<String, String> props = Map.of();

    // HASH partitioning
    String hashSql =
        operations.createSqlWithPartitionForTest(
            "T1", columns, props, new Transform[] {Transforms.bucket(4, new String[] {"ID"})});
    assertTrue(hashSql.contains("PARTITION BY HASH (\"ID\") PARTITIONS 4"));

    // Oracle rejects RANGE partitioning without at least one partition definition.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.createSqlWithPartitionForTest(
                "T1", columns, props, new Transform[] {Transforms.range(new String[] {"ID"})}));

    // RANGE partitioning with assignments
    String rangeWithPartsSql =
        operations.createSqlWithPartitionForTest(
            "T1",
            columns,
            props,
            new Transform[] {
              Transforms.range(
                  new String[] {"ID"},
                  new RangePartition[] {
                    Partitions.range("P1", Literals.longLiteral(100L), Literals.NULL, Map.of()),
                    Partitions.range(
                        "P_DATE", Literals.dateLiteral("2026-04-27"), Literals.NULL, Map.of()),
                    Partitions.range(
                        "P_TIMESTAMP",
                        Literals.timestampLiteral("2026-04-28T12:34:56"),
                        Literals.NULL,
                        Map.of()),
                    Partitions.range("P2", Literals.NULL, Literals.NULL, Map.of())
                  })
            });
    assertTrue(rangeWithPartsSql.contains("PARTITION BY RANGE (\"ID\")"));
    assertTrue(rangeWithPartsSql.contains("PARTITION \"P1\" VALUES LESS THAN (100)"));
    assertTrue(
        rangeWithPartsSql.contains("PARTITION \"P_DATE\" VALUES LESS THAN (DATE '2026-04-27')"));
    assertTrue(
        rangeWithPartsSql.contains(
            "PARTITION \"P_TIMESTAMP\" VALUES LESS THAN (TIMESTAMP '2026-04-28 12:34:56')"));
    assertTrue(rangeWithPartsSql.contains("PARTITION \"P2\" VALUES LESS THAN (MAXVALUE)"));

    // LIST partitioning without assignments
    String listSql =
        operations.createSqlWithPartitionForTest(
            "T1",
            columns,
            props,
            new Transform[] {Transforms.list(new String[][] {new String[] {"REGION"}})});
    assertTrue(listSql.contains("PARTITION BY LIST (\"REGION\")"));
    assertFalse(listSql.contains("VALUES"));

    // LIST partitioning with assignments
    String listWithPartsSql =
        operations.createSqlWithPartitionForTest(
            "T1",
            columns,
            props,
            new Transform[] {
              Transforms.list(
                  new String[][] {new String[] {"REGION"}},
                  new ListPartition[] {
                    Partitions.list(
                        "P_EAST",
                        new Literal<?>[][] {new Literal<?>[] {Literals.stringLiteral("East")}},
                        Map.of()),
                    Partitions.list(
                        "P_WEST",
                        new Literal<?>[][] {new Literal<?>[] {Literals.stringLiteral("West")}},
                        Map.of())
                  })
            });
    assertTrue(listWithPartsSql.contains("PARTITION BY LIST (\"REGION\")"));
    assertTrue(listWithPartsSql.contains("PARTITION \"P_EAST\" VALUES ('East')"));
    assertTrue(listWithPartsSql.contains("PARTITION \"P_WEST\" VALUES ('West')"));
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
    PreparedStatement columnTypeStmt = mock(PreparedStatement.class);
    ResultSet columnTypeRs = mock(ResultSet.class);
    PreparedStatement partitionsStmt = mock(PreparedStatement.class);
    ResultSet partitionsRs = mock(ResultSet.class);
    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet rangeBoundRs = mock(ResultSet.class);
    when(connection.prepareStatement(anyString()))
        .thenReturn(typeStmt2, colStmt2, columnTypeStmt, partitionsStmt);
    when(typeStmt2.executeQuery()).thenReturn(typeRs2);
    when(typeRs2.next()).thenReturn(true);
    when(typeRs2.getString("PARTITIONING_TYPE")).thenReturn("RANGE");
    when(typeRs2.getInt("PARTITION_COUNT")).thenReturn(2);
    when(colStmt2.executeQuery()).thenReturn(colRs2);
    when(colRs2.next()).thenReturn(true, false);
    when(colRs2.getString("COLUMN_NAME")).thenReturn("ORDER_DATE");
    when(columnTypeStmt.executeQuery()).thenReturn(columnTypeRs);
    when(columnTypeRs.next()).thenReturn(true);
    when(columnTypeRs.getString("DATA_TYPE")).thenReturn("TIMESTAMP(6)");
    when(columnTypeRs.getObject("DATA_PRECISION", Integer.class)).thenReturn(null);
    when(columnTypeRs.getObject("DATA_SCALE", Integer.class)).thenReturn(6);
    when(columnTypeRs.getObject("CHAR_LENGTH", Integer.class)).thenReturn(null);
    when(partitionsStmt.executeQuery()).thenReturn(partitionsRs);
    when(partitionsRs.next()).thenReturn(true, true, false);
    when(partitionsRs.getString("PARTITION_NAME")).thenReturn("P_2024", "P_MAX");
    when(partitionsRs.getString("HIGH_VALUE"))
        .thenReturn("TO_TIMESTAMP('2024-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')", "MAXVALUE");
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery(
            "SELECT (TO_TIMESTAMP('2024-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')) "
                + "FROM SYS.DUAL"))
        .thenReturn(rangeBoundRs);
    when(rangeBoundRs.next()).thenReturn(true, false);
    when(rangeBoundRs.getTimestamp(1)).thenReturn(Timestamp.valueOf("2024-01-01 00:00:00"));

    Transform[] rangeTransforms =
        operations.getTablePartitioningForTest(connection, "APP_USER", "T1");
    assertEquals(1, rangeTransforms.length);
    assertTrue(rangeTransforms[0] instanceof Transforms.RangeTransform);
    Transforms.RangeTransform rangeTransform = (Transforms.RangeTransform) rangeTransforms[0];
    assertEquals("ORDER_DATE", rangeTransform.fieldName()[0]);
    assertEquals(2, rangeTransform.assignments().length);
    assertEquals("P_2024", rangeTransform.assignments()[0].name());
    assertEquals(
        LocalDateTime.of(2024, 1, 1, 0, 0), rangeTransform.assignments()[0].upper().value());
    assertEquals(
        Types.TimestampType.withoutTimeZone(6), rangeTransform.assignments()[0].upper().dataType());
    assertEquals(Literals.NULL, rangeTransform.assignments()[0].lower());
    assertEquals("P_MAX", rangeTransform.assignments()[1].name());
    assertEquals(Literals.NULL, rangeTransform.assignments()[1].upper());
    verify(columnTypeStmt).setString(3, "ORDER_DATE");
    verify(partitionsStmt).setString(1, "APP_USER");
    verify(partitionsStmt).setString(2, "T1");
    verify(rangeBoundRs).getTimestamp(1);

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
    // tableName has already been normalized by OracleCatalogCapability.normalizeName, so it is
    // bound as-is, with its case preserved, not re-folded.
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
  void testGetTablePartitioningThrowsWhenPartitionColumnIsMissing() throws Exception {
    PreparedStatement typeStmt = mock(PreparedStatement.class);
    ResultSet typeRs = mock(ResultSet.class);
    PreparedStatement colStmt = mock(PreparedStatement.class);
    ResultSet colRs = mock(ResultSet.class);
    PreparedStatement columnTypeStmt = mock(PreparedStatement.class);
    ResultSet columnTypeRs = mock(ResultSet.class);

    when(connection.prepareStatement(anyString())).thenReturn(typeStmt, colStmt, columnTypeStmt);
    when(typeStmt.executeQuery()).thenReturn(typeRs);
    when(typeRs.next()).thenReturn(true);
    when(typeRs.getString("PARTITIONING_TYPE")).thenReturn("RANGE");
    when(colStmt.executeQuery()).thenReturn(colRs);
    when(colRs.next()).thenReturn(true, false);
    when(colRs.getString("COLUMN_NAME")).thenReturn("MISSING_COLUMN");
    when(columnTypeStmt.executeQuery()).thenReturn(columnTypeRs);
    when(columnTypeRs.next()).thenReturn(false);

    assertThrows(
        NoSuchColumnException.class,
        () -> operations.getTablePartitioningForTest(connection, "APP_USER", "T1"));
    verify(columnTypeStmt).setString(3, "MISSING_COLUMN");
  }

  @Test
  void testGetPartitionColumnTypeIgnoresZeroCharacterLengthForNumber() throws Exception {
    PreparedStatement columnTypeStmt = mock(PreparedStatement.class);
    ResultSet columnTypeRs = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(columnTypeStmt);
    when(columnTypeStmt.executeQuery()).thenReturn(columnTypeRs);
    when(columnTypeRs.next()).thenReturn(true);
    when(columnTypeRs.getString("DATA_TYPE")).thenReturn("NUMBER");
    when(columnTypeRs.getObject("DATA_PRECISION", Integer.class)).thenReturn(null);
    when(columnTypeRs.getObject("DATA_SCALE", Integer.class)).thenReturn(null);
    when(columnTypeRs.getObject("CHAR_LENGTH", Integer.class)).thenReturn(0);

    assertEquals(
        Types.ExternalType.of("NUMBER"),
        operations.getPartitionColumnType(connection, "APP_USER", "T1", "AMOUNT"));
    verify(columnTypeStmt).setString(1, "APP_USER");
    verify(columnTypeStmt).setString(2, "T1");
    verify(columnTypeStmt).setString(3, "AMOUNT");
  }

  @Test
  void testGetTablePartitioningBatchesNumericBounds() throws Exception {
    PreparedStatement typeStmt = mock(PreparedStatement.class);
    ResultSet typeRs = mock(ResultSet.class);
    PreparedStatement colStmt = mock(PreparedStatement.class);
    ResultSet colRs = mock(ResultSet.class);
    PreparedStatement columnTypeStmt = mock(PreparedStatement.class);
    ResultSet columnTypeRs = mock(ResultSet.class);
    PreparedStatement partitionsStmt = mock(PreparedStatement.class);
    ResultSet partitionsRs = mock(ResultSet.class);
    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet firstBatchRs = mock(ResultSet.class);
    ResultSet secondBatchRs = mock(ResultSet.class);

    when(connection.prepareStatement(anyString()))
        .thenReturn(typeStmt, colStmt, columnTypeStmt, partitionsStmt);
    when(typeStmt.executeQuery()).thenReturn(typeRs);
    when(typeRs.next()).thenReturn(true);
    when(typeRs.getString("PARTITIONING_TYPE")).thenReturn("RANGE");
    when(typeRs.getInt("PARTITION_COUNT")).thenReturn(102);
    when(colStmt.executeQuery()).thenReturn(colRs);
    when(colRs.next()).thenReturn(true, false);
    when(colRs.getString("COLUMN_NAME")).thenReturn("ID");
    when(columnTypeStmt.executeQuery()).thenReturn(columnTypeRs);
    when(columnTypeRs.next()).thenReturn(true);
    when(columnTypeRs.getString("DATA_TYPE")).thenReturn("NUMBER");
    when(columnTypeRs.getObject("DATA_PRECISION", Integer.class)).thenReturn(10);
    when(columnTypeRs.getObject("DATA_SCALE", Integer.class)).thenReturn(0);
    when(columnTypeRs.getObject("CHAR_LENGTH", Integer.class)).thenReturn(null);
    when(partitionsStmt.executeQuery()).thenReturn(partitionsRs);
    AtomicInteger partitionIndex = new AtomicInteger();
    when(partitionsRs.next()).thenAnswer(ignored -> partitionIndex.getAndIncrement() < 102);
    when(partitionsRs.getString("PARTITION_NAME"))
        .thenAnswer(
            ignored -> {
              int current = partitionIndex.get() - 1;
              return current < 101 ? "P_" + (current + 1) : "P_MAX";
            });
    when(partitionsRs.getString("HIGH_VALUE"))
        .thenAnswer(
            ignored -> {
              int current = partitionIndex.get() - 1;
              return current < 101 ? Integer.toString(current + 1) : "MAXVALUE";
            });
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery(anyString())).thenReturn(firstBatchRs, secondBatchRs);
    when(firstBatchRs.next()).thenReturn(true, false);
    when(firstBatchRs.getInt(anyInt()))
        .thenAnswer(invocation -> invocation.<Integer>getArgument(0));
    when(secondBatchRs.next()).thenReturn(true, false);
    when(secondBatchRs.getInt(1)).thenReturn(101);

    Transform[] transforms = operations.getTablePartitioningForTest(connection, "APP_USER", "T1");
    Transforms.RangeTransform rangeTransform = (Transforms.RangeTransform) transforms[0];
    assertEquals(102, rangeTransform.assignments().length);
    assertEquals(1, rangeTransform.assignments()[0].upper().value());
    assertEquals(100, rangeTransform.assignments()[99].upper().value());
    assertEquals(101, rangeTransform.assignments()[100].upper().value());
    assertEquals(Types.IntegerType.get(), rangeTransform.assignments()[100].upper().dataType());
    assertEquals(Literals.NULL, rangeTransform.assignments()[101].upper());

    ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
    verify(rangeBoundStmt, times(2)).executeQuery(queryCaptor.capture());
    List<String> queries = queryCaptor.getAllValues();
    assertTrue(queries.get(0).startsWith("SELECT (1), (2)"));
    assertTrue(queries.get(0).endsWith("(100) FROM SYS.DUAL"));
    assertEquals("SELECT (101) FROM SYS.DUAL", queries.get(1));
    assertFalse(queries.stream().anyMatch(query -> query.contains("MAXVALUE")));
  }

  @Test
  void testEvaluateRangeUpperBoundsSplitsLongQueries() throws Exception {
    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet firstBatchRs = mock(ResultSet.class);
    ResultSet secondBatchRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery(anyString())).thenReturn(firstBatchRs, secondBatchRs);
    when(firstBatchRs.next()).thenReturn(true, false);
    when(firstBatchRs.getInt(1)).thenReturn(1);
    when(firstBatchRs.getInt(2)).thenReturn(2);
    when(secondBatchRs.next()).thenReturn(true, false);
    when(secondBatchRs.getInt(1)).thenReturn(3);

    String padding = "x".repeat(14_000);
    String firstExpression = "1 /*" + padding + "*/";
    String secondExpression = "2 /*" + padding + "*/";
    String thirdExpression = "3 /*" + padding + "*/";
    Literal<?>[] bounds =
        operations.evaluateRangeUpperBounds(
            connection,
            List.of(firstExpression, secondExpression, thirdExpression),
            Types.IntegerType.get());

    assertEquals(1, bounds[0].value());
    assertEquals(2, bounds[1].value());
    assertEquals(3, bounds[2].value());
    ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
    verify(rangeBoundStmt, times(2)).executeQuery(queryCaptor.capture());
    assertTrue(queryCaptor.getAllValues().get(0).contains(firstExpression));
    assertTrue(queryCaptor.getAllValues().get(0).contains(secondExpression));
    assertFalse(queryCaptor.getAllValues().get(0).contains(thirdExpression));
    assertEquals(
        "SELECT (" + thirdExpression + ") FROM SYS.DUAL", queryCaptor.getAllValues().get(1));
  }

  @Test
  void testEvaluateRangeUpperBoundsRejectsInvalidResults() throws Exception {
    SQLException emptyExpressionException =
        assertThrows(
            SQLException.class,
            () ->
                operations.evaluateRangeUpperBounds(
                    connection, List.of(" "), Types.IntegerType.get()));
    assertTrue(emptyExpressionException.getMessage().contains("empty"));

    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet noRowsRs = mock(ResultSet.class);
    ResultSet nullValueRs = mock(ResultSet.class);
    ResultSet multipleRowsRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery(anyString()))
        .thenReturn(noRowsRs, nullValueRs, multipleRowsRs);
    when(noRowsRs.next()).thenReturn(false);
    when(nullValueRs.next()).thenReturn(true, false);
    when(nullValueRs.wasNull()).thenReturn(true);
    when(multipleRowsRs.next()).thenReturn(true, true);
    when(multipleRowsRs.getInt(1)).thenReturn(1);

    SQLException noRowsException =
        assertThrows(
            SQLException.class,
            () ->
                operations.evaluateRangeUpperBounds(
                    connection, List.of("1"), Types.IntegerType.get()));
    assertTrue(noRowsException.getMessage().contains("no row"));

    SQLException nullValueException =
        assertThrows(
            SQLException.class,
            () ->
                operations.evaluateRangeUpperBounds(
                    connection, List.of("2"), Types.IntegerType.get()));
    assertTrue(nullValueException.getMessage().contains("NULL"));

    SQLException multipleRowsException =
        assertThrows(
            SQLException.class,
            () ->
                operations.evaluateRangeUpperBounds(
                    connection, List.of("3"), Types.IntegerType.get()));
    assertTrue(multipleRowsException.getMessage().contains("multiple rows"));
  }

  @Test
  void testEvaluateRawRangeUpperBoundAsHexText() throws Exception {
    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet rangeBoundRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery("SELECT (HEXTORAW('0AFF')) FROM SYS.DUAL"))
        .thenReturn(rangeBoundRs);
    when(rangeBoundRs.next()).thenReturn(true, false);
    when(rangeBoundRs.getString(1)).thenReturn("0AFF");

    Literal<?>[] bounds =
        operations.evaluateRangeUpperBounds(
            connection, List.of("HEXTORAW('0AFF')"), Types.BinaryType.get());

    assertEquals("0AFF", bounds[0].value());
    assertEquals(Types.BinaryType.get(), bounds[0].dataType());
    verify(rangeBoundRs).getString(1);
  }

  @Test
  void testEvaluateNumberOneRangeBoundsWithoutCollapsingValues() throws Exception {
    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet rangeBoundRs = mock(ResultSet.class);
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery("SELECT (1), (2) FROM SYS.DUAL")).thenReturn(rangeBoundRs);
    when(rangeBoundRs.next()).thenReturn(true, false);
    when(rangeBoundRs.getBigDecimal(1)).thenReturn(BigDecimal.ONE);
    when(rangeBoundRs.getBigDecimal(2)).thenReturn(new BigDecimal("2"));

    Literal<?>[] bounds =
        operations.evaluateRangeUpperBounds(connection, List.of("1", "2"), Types.BooleanType.get());

    assertEquals(true, bounds[0].value());
    assertEquals(new BigDecimal("2"), bounds[1].value());
    assertEquals(Types.BooleanType.get(), bounds[0].dataType());
    assertEquals(Types.BooleanType.get(), bounds[1].dataType());
    verify(rangeBoundRs, times(2)).getBigDecimal(anyInt());
    verify(rangeBoundRs, never()).getBoolean(anyInt());
  }

  @Test
  void testGetTablePartitioningPreservesCharacterBoundWhitespace() throws Exception {
    PreparedStatement typeStmt = mock(PreparedStatement.class);
    ResultSet typeRs = mock(ResultSet.class);
    PreparedStatement colStmt = mock(PreparedStatement.class);
    ResultSet colRs = mock(ResultSet.class);
    PreparedStatement columnTypeStmt = mock(PreparedStatement.class);
    ResultSet columnTypeRs = mock(ResultSet.class);
    PreparedStatement partitionsStmt = mock(PreparedStatement.class);
    ResultSet partitionsRs = mock(ResultSet.class);
    Statement rangeBoundStmt = mock(Statement.class);
    ResultSet rangeBoundRs = mock(ResultSet.class);

    when(connection.prepareStatement(anyString()))
        .thenReturn(typeStmt, colStmt, columnTypeStmt, partitionsStmt);
    when(typeStmt.executeQuery()).thenReturn(typeRs);
    when(typeRs.next()).thenReturn(true);
    when(typeRs.getString("PARTITIONING_TYPE")).thenReturn("RANGE");
    when(typeRs.getInt("PARTITION_COUNT")).thenReturn(2);
    when(colStmt.executeQuery()).thenReturn(colRs);
    when(colRs.next()).thenReturn(true, false);
    when(colRs.getString("COLUMN_NAME")).thenReturn("CODE");
    when(columnTypeStmt.executeQuery()).thenReturn(columnTypeRs);
    when(columnTypeRs.next()).thenReturn(true);
    when(columnTypeRs.getString("DATA_TYPE")).thenReturn("VARCHAR2");
    when(columnTypeRs.getObject("DATA_PRECISION", Integer.class)).thenReturn(null);
    when(columnTypeRs.getObject("DATA_SCALE", Integer.class)).thenReturn(null);
    when(columnTypeRs.getObject("CHAR_LENGTH", Integer.class)).thenReturn(10);
    when(partitionsStmt.executeQuery()).thenReturn(partitionsRs);
    when(partitionsRs.next()).thenReturn(true, true, false);
    when(partitionsRs.getString("PARTITION_NAME")).thenReturn("P_CODE", "P_MAX");
    when(partitionsRs.getString("HIGH_VALUE")).thenReturn("RPAD(' A ', 5, ' ')", "MAXVALUE");
    when(connection.createStatement()).thenReturn(rangeBoundStmt);
    when(rangeBoundStmt.executeQuery("SELECT (RPAD(' A ', 5, ' ')) FROM SYS.DUAL"))
        .thenReturn(rangeBoundRs);
    when(rangeBoundRs.next()).thenReturn(true, false);
    when(rangeBoundRs.getString(1)).thenReturn(" A   ");

    Transform[] transforms = operations.getTablePartitioningForTest(connection, "APP_USER", "T1");
    Transforms.RangeTransform rangeTransform = (Transforms.RangeTransform) transforms[0];
    assertEquals(" A   ", rangeTransform.assignments()[0].upper().value());
    assertEquals(Types.VarCharType.of(10), rangeTransform.assignments()[0].upper().dataType());
    assertEquals(Literals.NULL, rangeTransform.assignments()[1].upper());
    verify(rangeBoundRs).getString(1);
  }

  @Test
  void testCommentPropertyIsHiddenAndReserved() {
    OracleTablePropertiesMetadata metadata = new OracleTablePropertiesMetadata();

    assertTrue(metadata.isHiddenProperty(COMMENT_KEY));
    assertTrue(metadata.isReservedProperty(COMMENT_KEY));
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
    Connection schemaConnection = operations.getConnection("APP_USER");
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
    // tableName has already been normalized by core, so it is passed to DatabaseMetaData as-is.
    assertEquals(table, operations.getTableForTest(connection, "APP_USER", "T1"));
    assertEquals(columns, operations.getColumnsForTest(connection, "APP_USER", "T1"));

    assertFalse(operations.getAutoIncrementInfoForTest(null));
    assertEquals(0, operations.getTablePartitioningForTest(connection, "APP_USER", "T1").length);

    assertEquals(
        "ALTER TABLE \"T1\" RENAME TO \"T2\"", operations.generateRenameTableSql("T1", "T2"));
    assertEquals("", operations.generateAlterTableSql("APP_USER", "T1", new TableChange[0]));
  }

  @Test
  void testListTablesReturnsPhysicalNamesAsIs() throws Exception {
    // listTables() returns names exactly as Oracle stores them, with no synthetic quoting added:
    // Capability.normalizeName is not idempotent for a catalog whose folding depends on whether the
    // name was originally quoted, and TableNormalizeDispatcher.listTables does not re-normalize
    // this result, so returning an already-canonical physical name here is required for
    // correctness.
    Statement sessionStatement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(sessionStatement);
    when(connection.getSchema()).thenReturn("APP_USER");
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(metaData);

    ResultSet tables = mock(ResultSet.class);
    when(metaData.getTables(null, "APP_USER", null, new String[] {"TABLE"})).thenReturn(tables);
    when(tables.next()).thenReturn(true, true, true, false);
    when(tables.getString("TABLE_SCHEM")).thenReturn("APP_USER", "APP_USER", "APP_USER");
    when(tables.getString("TABLE_NAME")).thenReturn("foo", "Foo", "BAR");

    assertEquals(List.of("foo", "Foo", "BAR"), operations.listTables("APP_USER"));
  }

  @Test
  void testGenerateAlterTableSql() {
    // Column and table names have already been normalized to their physical (uppercase) form by
    // core before reaching this method, which only quotes them.
    String sql =
        operations.generateAlterTableSql(
            "APP_USER",
            "T1",
            TableChange.addColumn(
                new String[] {"NEW_COL"},
                Types.VarCharType.of(100),
                "new col comment",
                TableChange.ColumnPosition.defaultPos(),
                true,
                false,
                Literals.stringLiteral("v1")),
            TableChange.updateColumnType(new String[] {"NEW_COL"}, Types.LongType.get()),
            TableChange.updateColumnDefaultValue(
                new String[] {"NEW_COL"}, Literals.longLiteral(10L)),
            TableChange.updateColumnNullability(new String[] {"NEW_COL"}, false),
            TableChange.renameColumn(new String[] {"NEW_COL"}, "NEW_COL_2"),
            TableChange.updateColumnComment(new String[] {"NEW_COL_2"}, "updated"),
            TableChange.deleteColumn(new String[] {"OLD_COL"}, false),
            TableChange.updateComment("table comment"));

    assertTrue(
        sql.contains(
            "ALTER TABLE \"APP_USER\".\"T1\" ADD (\"NEW_COL\" VARCHAR2(100) DEFAULT 'v1')"));
    assertTrue(
        sql.contains("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"NEW_COL\" IS 'new col comment'"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" MODIFY (\"NEW_COL\" NUMBER(19))"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" MODIFY (\"NEW_COL\" DEFAULT 10)"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" MODIFY (\"NEW_COL\" NOT NULL)"));
    assertTrue(
        sql.contains("ALTER TABLE \"APP_USER\".\"T1\" RENAME COLUMN \"NEW_COL\" TO \"NEW_COL_2\""));
    assertTrue(sql.contains("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"NEW_COL_2\" IS 'updated'"));
    assertTrue(sql.contains("ALTER TABLE \"APP_USER\".\"T1\" DROP COLUMN \"OLD_COL\""));
    assertTrue(sql.contains("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'"));
  }

  @Test
  void testGenerateAlterTableSqlUsesGivenSchemaNameAsIs() {
    // The schema name has already been normalized by core before reaching this method, so it is
    // quoted as-is, with no further folding.
    String sql =
        operations.generateAlterTableSql(
            "app_user", "T1", TableChange.updateComment("table comment"));

    assertEquals("COMMENT ON TABLE \"app_user\".\"T1\" IS 'table comment'", sql);
  }

  @Test
  void testGenerateAlterTableSqlSupportsNullColumnComment() {
    String sql =
        operations.generateAlterTableSql(
            "APP_USER", "T1", TableChange.updateColumnComment(new String[] {"COL_A"}, null));

    assertEquals("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"COL_A\" IS NULL", sql);
  }

  @Test
  void testAlterTableExecutesGeneratedSqls() throws Exception {
    Statement sessionStatement = mock(Statement.class);
    Statement ddlStatement1 = mock(Statement.class);
    Statement ddlStatement2 = mock(Statement.class);
    when(connection.createStatement()).thenReturn(sessionStatement, ddlStatement1, ddlStatement2);

    operations.alterTable(
        "APP_USER",
        "T1",
        TableChange.updateComment("table comment"),
        TableChange.updateColumnComment(new String[] {"COL_A"}, "column comment"));

    verify(sessionStatement).execute(eq("ALTER SESSION SET CURRENT_SCHEMA = \"APP_USER\""));
    verify(connection).setSchema("APP_USER");
    verify(ddlStatement1)
        .executeUpdate(eq("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'"));
    verify(ddlStatement2)
        .executeUpdate(eq("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"COL_A\" IS 'column comment'"));
  }

  @Test
  void testAlterTableThrowsWhenExecutingGeneratedSqlFails() throws Exception {
    Statement sessionStatement = mock(Statement.class);
    Statement ddlStatement1 = mock(Statement.class);
    Statement ddlStatement2 = mock(Statement.class);
    when(connection.createStatement()).thenReturn(sessionStatement, ddlStatement1, ddlStatement2);
    when(ddlStatement2.executeUpdate(
            eq("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"COL_A\" IS 'column comment'")))
        .thenThrow(new SQLException("failed"));

    assertThrows(
        GravitinoRuntimeException.class,
        () ->
            operations.alterTable(
                "APP_USER",
                "T1",
                TableChange.updateComment("table comment"),
                TableChange.updateColumnComment(new String[] {"COL_A"}, "column comment")));

    verify(ddlStatement1)
        .executeUpdate(eq("COMMENT ON TABLE \"APP_USER\".\"T1\" IS 'table comment'"));
    verify(ddlStatement2)
        .executeUpdate(eq("COMMENT ON COLUMN \"APP_USER\".\"T1\".\"COL_A\" IS 'column comment'"));
  }

  @Test
  void testAlterTableSkipsExecutionWhenNoChanges() throws Exception {
    operations.alterTable("APP_USER", "T1", new TableChange[0]);

    verify(dataSource, never()).getConnection();
  }

  @Test
  void testGenerateAlterTableSqlIndexChanges() {
    // Column names referenced by an index have already been normalized to their physical
    // (uppercase) form by core before reaching this method, which only quotes them. Index names
    // themselves are not covered by that normalization and are quoted as given.
    String addPkSql =
        operations.generateAlterTableSql(
            "APP_USER",
            "T1",
            TableChange.addIndex(
                Index.IndexType.PRIMARY_KEY, "PK_T1", new String[][] {new String[] {"ID"}}));
    assertTrue(
        addPkSql.contains(
            "ALTER TABLE \"APP_USER\".\"T1\" ADD CONSTRAINT \"PK_T1\" PRIMARY KEY (\"ID\")"));

    String addUkSql =
        operations.generateAlterTableSql(
            "APP_USER",
            "T1",
            TableChange.addIndex(
                Index.IndexType.UNIQUE_KEY, "UK_T1_NAME", new String[][] {new String[] {"NAME"}}));
    assertTrue(
        addUkSql.contains(
            "ALTER TABLE \"APP_USER\".\"T1\" ADD CONSTRAINT \"UK_T1_NAME\" UNIQUE (\"NAME\")"));

    String dropSql =
        operations.generateAlterTableSql("APP_USER", "T1", TableChange.deleteIndex("PK_T1", false));
    assertTrue(dropSql.contains("ALTER TABLE \"APP_USER\".\"T1\" DROP CONSTRAINT \"PK_T1\""));

    operations.tableForLoad =
        JdbcTable.builder()
            .withName("T1")
            .withDatabaseName("APP_USER")
            .withColumns(new JdbcColumn[0])
            .withIndexes(new Index[] {Indexes.primary("PK_T1", new String[][] {{"ID"}})})
            .build();

    String skipMissingIndexSql =
        operations.generateAlterTableSql(
            "APP_USER", "T1", TableChange.deleteIndex("MISSING_INDEX", true));
    assertEquals("", skipMissingIndexSql);

    String dropExistingIndexSql =
        operations.generateAlterTableSql("APP_USER", "T1", TableChange.deleteIndex("PK_T1", true));
    assertTrue(
        dropExistingIndexSql.contains("ALTER TABLE \"APP_USER\".\"T1\" DROP CONSTRAINT \"PK_T1\""));

    // Index names are quoted case-preserving (not folded), matching Oracle's own case-sensitive
    // quoted-identifier semantics, so a differently-cased reference does not match the existing
    // index and is skipped, exactly like Oracle would treat "pk_t1" and "PK_T1" as distinct.
    String skipDifferentCaseIndexSql =
        operations.generateAlterTableSql("APP_USER", "T1", TableChange.deleteIndex("pk_t1", true));
    assertEquals("", skipDifferentCaseIndexSql);
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
  void testGetConnectionPreservesCaseForMixedCaseSchemaName() throws Exception {
    // databaseName has already been normalized (unquoted, case preserved) by
    // OracleCatalogCapability.normalizeName before reaching this method, so it is used as-is,
    // without any further folding.
    Statement statement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.getSchema()).thenReturn("MySchema");

    Connection schemaConnection = operations.getConnection("MySchema");
    assertEquals(connection, schemaConnection);
    verify(statement).execute(eq("ALTER SESSION SET CURRENT_SCHEMA = \"MySchema\""));
    verify(connection).setSchema("MySchema");
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
