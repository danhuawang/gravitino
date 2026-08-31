/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.operation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.maxcompute.converter.MaxComputeExceptionConverter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import javax.sql.DataSource;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.exceptions.NoSuchPartitionException;
import org.apache.gravitino.exceptions.PartitionAlreadyExistsException;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.partitions.IdentityPartition;
import org.apache.gravitino.rel.partitions.Partition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link MaxComputeTablePartitionOperations}. */
public class TestMaxComputeTablePartitionOperations {

  private static final String PROJECT_NAME = "test_project";

  @Mock private DataSource dataSource;

  @Mock private Connection connection;

  @Mock private Statement statement;

  @Mock private ResultSet resultSet;

  private AutoCloseable mocks;
  private MaxComputeExceptionConverter exceptionConverter;
  private JdbcTable partitionedTable;

  @BeforeEach
  void setUp() throws SQLException {
    mocks = MockitoAnnotations.openMocks(this);
    exceptionConverter = new MaxComputeExceptionConverter();

    // Create a partitioned table for testing
    JdbcColumn[] columns =
        new JdbcColumn[] {
          JdbcColumn.builder()
              .withName("id")
              .withType(Types.LongType.get())
              .withNullable(false)
              .build(),
          JdbcColumn.builder()
              .withName("name")
              .withType(Types.StringType.get())
              .withNullable(true)
              .build(),
          JdbcColumn.builder()
              .withName("pt")
              .withType(Types.StringType.get())
              .withNullable(false)
              .build()
        };

    Transform[] partitioning = new Transform[] {Transforms.identity("pt")};

    partitionedTable =
        JdbcTable.builder()
            .withName("test_table")
            .withDatabaseName("test_schema")
            .withColumns(columns)
            .withPartitioning(partitioning)
            .build();

    // Setup mock behavior
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  void testListPartitionNames() throws Exception {
    // Setup mock to return partition names
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, true, false);
    when(resultSet.getString(1)).thenReturn("pt=2024-01-01", "pt=2024-01-02");

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      String[] partitionNames = ops.listPartitionNames();

      assertNotNull(partitionNames);
      assertEquals(2, partitionNames.length);
      assertEquals("pt=2024-01-01", partitionNames[0]);
      assertEquals("pt=2024-01-02", partitionNames[1]);
    }
  }

  @Test
  void testListPartitionNamesEmpty() throws Exception {
    // Setup mock to return no partitions
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(false);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      String[] partitionNames = ops.listPartitionNames();

      assertNotNull(partitionNames);
      assertEquals(0, partitionNames.length);
    }
  }

  @Test
  void testListPartitions() throws Exception {
    // Setup mock to return partition names
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, true, false);
    when(resultSet.getString(1)).thenReturn("pt=2024-01-01", "pt=2024-01-02");

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      Partition[] partitions = ops.listPartitions();

      assertNotNull(partitions);
      assertEquals(2, partitions.length);

      // Verify first partition
      assertInstanceOf(IdentityPartition.class, partitions[0]);
      IdentityPartition p1 = (IdentityPartition) partitions[0];
      assertEquals("pt=2024-01-01", p1.name());
      assertArrayEquals(new String[] {"pt"}, p1.fieldNames()[0]);
      assertEquals("2024-01-01", p1.values()[0].value());

      // Verify second partition
      assertInstanceOf(IdentityPartition.class, partitions[1]);
      IdentityPartition p2 = (IdentityPartition) partitions[1];
      assertEquals("pt=2024-01-02", p2.name());
    }
  }

  @Test
  void testListPartitionsMultiLevel() throws Exception {
    // Create a multi-level partitioned table
    Transform[] partitioning =
        new Transform[] {Transforms.identity("pt"), Transforms.identity("region")};

    JdbcTable multiPartTable =
        JdbcTable.builder()
            .withName("multi_part_table")
            .withDatabaseName("test_schema")
            .withColumns(
                new JdbcColumn[] {
                  JdbcColumn.builder()
                      .withName("id")
                      .withType(Types.LongType.get())
                      .withNullable(false)
                      .build(),
                  JdbcColumn.builder()
                      .withName("pt")
                      .withType(Types.StringType.get())
                      .withNullable(false)
                      .build(),
                  JdbcColumn.builder()
                      .withName("region")
                      .withType(Types.StringType.get())
                      .withNullable(false)
                      .build()
                })
            .withPartitioning(partitioning)
            .build();

    // Setup mock to return multi-level partition names
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString(1)).thenReturn("pt=2024-01-01/region=us");

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, multiPartTable, exceptionConverter, PROJECT_NAME)) {
      Partition[] partitions = ops.listPartitions();

      assertNotNull(partitions);
      assertEquals(1, partitions.length);

      assertInstanceOf(IdentityPartition.class, partitions[0]);
      IdentityPartition p = (IdentityPartition) partitions[0];
      assertEquals("pt=2024-01-01/region=us", p.name());
      assertEquals(2, p.fieldNames().length);
      assertEquals("pt", p.fieldNames()[0][0]);
      assertEquals("region", p.fieldNames()[1][0]);
      assertEquals("2024-01-01", p.values()[0].value());
      assertEquals("us", p.values()[1].value());
    }
  }

  @Test
  void testGetPartition() throws Exception {
    // Setup mock to return partition names for existence check
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString(1)).thenReturn("pt=2024-01-01");

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      Partition partition = ops.getPartition("pt=2024-01-01");

      assertNotNull(partition);
      assertInstanceOf(IdentityPartition.class, partition);
      assertEquals("pt=2024-01-01", partition.name());
    }
  }

  @Test
  void testGetPartitionNotFound() throws Exception {
    // Setup mock to return no matching partition
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(false);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      assertThrows(NoSuchPartitionException.class, () -> ops.getPartition("pt=non-existent"));
    }
  }

  @Test
  void testAddPartition() throws Exception {
    // Setup mock for successful partition addition
    when(statement.execute(anyString())).thenReturn(true);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      IdentityPartition newPartition =
          Partitions.identity(
              "pt=2024-01-03",
              new String[][] {{"pt"}},
              new org.apache.gravitino.rel.expressions.literals.Literal[] {
                Literals.stringLiteral("2024-01-03")
              },
              Collections.emptyMap());

      Partition added = ops.addPartition(newPartition);

      assertNotNull(added);
      assertInstanceOf(IdentityPartition.class, added);
      assertEquals("pt=2024-01-03", added.name());
    }
  }

  @Test
  void testAddPartitionAlreadyExists() throws Exception {
    // Setup mock to throw exception for existing partition
    SQLException sqlException = new SQLException("Partition already exists");
    when(statement.execute(anyString())).thenThrow(sqlException);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      IdentityPartition existingPartition =
          Partitions.identity(
              "pt=2024-01-01",
              new String[][] {{"pt"}},
              new org.apache.gravitino.rel.expressions.literals.Literal[] {
                Literals.stringLiteral("2024-01-01")
              },
              Collections.emptyMap());

      assertThrows(
          PartitionAlreadyExistsException.class, () -> ops.addPartition(existingPartition));
    }
  }

  @Test
  void testAddPartitionInvalidType() throws Exception {
    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      // Try to add a non-identity partition (e.g., range partition)
      Partition rangePartition =
          Partitions.range("p1", Literals.NULL, Literals.NULL, Collections.emptyMap());

      assertThrows(IllegalArgumentException.class, () -> ops.addPartition(rangePartition));
    }
  }

  @Test
  void testDropPartition() throws Exception {
    // Setup mock for successful partition drop
    when(statement.execute(anyString())).thenReturn(true);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      boolean result = ops.dropPartition("pt=2024-01-01");

      assertTrue(result);
    }
  }

  @Test
  void testDropPartitionNotFound() throws Exception {
    // Setup mock to throw exception for non-existent partition
    SQLException sqlException = new SQLException("Partition not found");
    when(statement.execute(anyString())).thenThrow(sqlException);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      boolean result = ops.dropPartition("pt=non-existent");

      assertFalse(result);
    }
  }

  @Test
  void testPartitionExists() throws Exception {
    // Setup mock to return partition names
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getString(1)).thenReturn("pt=2024-01-01");

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      assertTrue(ops.partitionExists("pt=2024-01-01"));
    }
  }

  @Test
  void testPartitionNotExists() throws Exception {
    // Setup mock to return no matching partition
    when(statement.execute(anyString())).thenReturn(true);
    when(statement.getResultSet()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(false);

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, partitionedTable, exceptionConverter, PROJECT_NAME)) {
      assertFalse(ops.partitionExists("pt=non-existent"));
    }
  }

  @Test
  void testNonPartitionedTable() throws Exception {
    // Create a non-partitioned table
    JdbcTable nonPartTable =
        JdbcTable.builder()
            .withName("non_part_table")
            .withDatabaseName("test_schema")
            .withColumns(
                new JdbcColumn[] {
                  JdbcColumn.builder()
                      .withName("id")
                      .withType(Types.LongType.get())
                      .withNullable(false)
                      .build()
                })
            .withPartitioning(Transforms.EMPTY_TRANSFORM)
            .build();

    try (MaxComputeTablePartitionOperations ops =
        new MaxComputeTablePartitionOperations(
            dataSource, nonPartTable, exceptionConverter, PROJECT_NAME)) {
      // Adding partition to non-partitioned table should fail
      IdentityPartition partition =
          Partitions.identity(
              "pt=2024-01-01",
              new String[][] {{"pt"}},
              new org.apache.gravitino.rel.expressions.literals.Literal[] {
                Literals.stringLiteral("2024-01-01")
              },
              Collections.emptyMap());

      assertThrows(IllegalArgumentException.class, () -> ops.addPartition(partition));
    }
  }
}
