/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.operation;

import static com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeUtils.escapeSql;
import static com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeUtils.quoteTableName;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTablePartitionOperations;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchPartitionException;
import org.apache.gravitino.exceptions.PartitionAlreadyExistsException;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.partitions.IdentityPartition;
import org.apache.gravitino.rel.partitions.Partition;
import org.apache.gravitino.rel.partitions.Partitions;
import org.apache.gravitino.rel.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Table partition operations for MaxCompute.
 *
 * <p>MaxCompute supports identity partitioning with up to 6 partition levels. Partition operations
 * include:
 *
 * <ul>
 *   <li>SHOW PARTITIONS table_name - List all partitions
 *   <li>ALTER TABLE table_name ADD PARTITION (pt_col='value') - Add a partition
 *   <li>ALTER TABLE table_name DROP PARTITION (pt_col='value') - Drop a partition
 *   <li>DESC table_name PARTITION (pt_col='value') - Get partition details
 * </ul>
 *
 * <p>MaxCompute requires three-layer namespace: project.schema.table
 */
public final class MaxComputeTablePartitionOperations extends JdbcTablePartitionOperations {

  private static final Logger LOG =
      LoggerFactory.getLogger(MaxComputeTablePartitionOperations.class);

  private final JdbcExceptionConverter exceptionConverter;
  private final String projectName;

  public MaxComputeTablePartitionOperations(
      DataSource dataSource,
      JdbcTable loadedTable,
      JdbcExceptionConverter exceptionConverter,
      String projectName) {
    super(dataSource, loadedTable);
    checkArgument(exceptionConverter != null, "exceptionConverter is null");
    checkArgument(StringUtils.isNotBlank(projectName), "projectName is blank");
    this.exceptionConverter = exceptionConverter;
    this.projectName = projectName;
    LOG.info(
        "MaxComputeTablePartitionOperations created with projectName={}, schema={}, table={}",
        projectName,
        loadedTable.databaseName(),
        loadedTable.name());
  }

  /**
   * Gets a connection without setting catalog.
   *
   * <p>MaxCompute uses three-layer namespace (project.schema.table). The project is already
   * specified in the JDBC URL, so we should NOT call setCatalog() which would incorrectly try to
   * switch to a different project.
   *
   * @return a database connection
   * @throws SQLException if a database access error occurs
   */
  @Override
  protected Connection getConnection(String databaseName) throws SQLException {
    // Do NOT call setCatalog() - MaxCompute project is already in JDBC URL
    // The databaseName parameter is actually the schema name in MaxCompute's three-layer namespace
    return dataSource.getConnection();
  }

  @Override
  public String[] listPartitionNames() {
    String qualifiedTableName = getQualifiedTableName();
    String showPartitionsSql =
        String.format("SHOW PARTITIONS %s", quoteTableName(qualifiedTableName));

    try (Connection connection = getConnection(loadedTable.databaseName());
        Statement statement = connection.createStatement()) {
      LOG.debug("Executing SQL: {}", showPartitionsSql);
      statement.execute(showPartitionsSql);

      try (ResultSet result = statement.getResultSet()) {
        ImmutableList.Builder<String> partitionNames = ImmutableList.builder();
        while (result.next()) {
          // MaxCompute JDBC driver returns all partitions as a single string separated by newlines
          // Format: pt_col=value\npt_col=value2\n or pt_col1=v1/pt_col2=v2\npt_col1=v3/pt_col2=v4\n
          String partitionSpec = result.getString(1);
          if (StringUtils.isNotBlank(partitionSpec)) {
            // Split by newline and add each partition separately
            String[] parts = partitionSpec.split("\n");
            for (String part : parts) {
              String trimmed = part.trim();
              if (StringUtils.isNotBlank(trimmed)) {
                partitionNames.add(trimmed);
              }
            }
          }
        }
        return partitionNames.build().toArray(new String[0]);
      }
    } catch (SQLException e) {
      throw exceptionConverter.toGravitinoException(e);
    }
  }

  @Override
  public Partition[] listPartitions() {
    String[] partitionNames = listPartitionNames();
    if (ArrayUtils.isEmpty(partitionNames)) {
      return Partitions.EMPTY_PARTITIONS;
    }

    Transform[] partitioning = loadedTable.partitioning();
    if (ArrayUtils.isEmpty(partitioning)) {
      // This can happen if the table metadata was loaded incorrectly or if there's
      // a mismatch between SHOW PARTITIONS result and table schema. Log a warning
      // and return empty to avoid errors.
      LOG.warn(
          "Table {} has partitions returned by SHOW PARTITIONS but no partitioning info in schema",
          loadedTable.name());
      return Partitions.EMPTY_PARTITIONS;
    }

    // Get partition field names from the table's partitioning info
    String[][] fieldNames = extractPartitionFieldNames(partitioning);

    ImmutableList.Builder<Partition> partitions = ImmutableList.builder();
    for (String partitionName : partitionNames) {
      Partition partition = parsePartitionSpec(partitionName, fieldNames);
      if (partition != null) {
        partitions.add(partition);
      }
    }
    return partitions.build().toArray(new Partition[0]);
  }

  @Override
  public Partition getPartition(String partitionName) throws NoSuchPartitionException {
    Transform[] partitioning = loadedTable.partitioning();
    if (ArrayUtils.isEmpty(partitioning)) {
      throw new NoSuchPartitionException(
          "Table %s has partitions returned by SHOW PARTITIONS but no partitioning info in schema",
          loadedTable.name());
    }

    // Verify partition exists by checking if it's in the list
    String[] existingPartitions = listPartitionNames();
    boolean exists =
        Arrays.stream(existingPartitions)
            .anyMatch(p -> normalizePartitionName(p).equals(normalizePartitionName(partitionName)));

    if (!exists) {
      throw new NoSuchPartitionException(
          "Partition %s does not exist in table %s", partitionName, loadedTable.name());
    }

    String[][] fieldNames = extractPartitionFieldNames(partitioning);
    Partition partition = parsePartitionSpec(partitionName, fieldNames);
    if (partition == null) {
      throw new NoSuchPartitionException(
          "Failed to parse partition %s in table %s", partitionName, loadedTable.name());
    }
    return partition;
  }

  @Override
  public Partition addPartition(Partition partition) throws PartitionAlreadyExistsException {
    checkArgument(partition != null, "partition is null");
    checkArgument(
        partition instanceof IdentityPartition,
        "MaxCompute only supports identity partitions, but got %s",
        partition.getClass().getSimpleName());

    IdentityPartition identityPartition = (IdentityPartition) partition;
    validatePartitionSpec(identityPartition);

    String qualifiedTableName = getQualifiedTableName();
    String partitionSpec = generatePartitionSpec(identityPartition);
    String addPartitionSql =
        String.format(
            "ALTER TABLE %s ADD PARTITION (%s)", quoteTableName(qualifiedTableName), partitionSpec);

    try (Connection connection = getConnection(loadedTable.databaseName());
        Statement statement = connection.createStatement()) {
      LOG.info("Add partition SQL: {}", addPartitionSql);
      statement.execute(addPartitionSql);

      // Return the partition with empty properties (MaxCompute doesn't support partition
      // properties)
      return Partitions.identity(
          identityPartition.name(),
          identityPartition.fieldNames(),
          identityPartition.values(),
          Collections.emptyMap());
    } catch (SQLException e) {
      GravitinoRuntimeException exception = exceptionConverter.toGravitinoException(e);
      if (exception.getMessage() != null
          && exception.getMessage().toLowerCase().contains("already exists")) {
        throw new PartitionAlreadyExistsException(
            "Partition %s already exists in table %s", partitionSpec, loadedTable.name());
      }
      throw exception;
    }
  }

  @Override
  public boolean dropPartition(String partitionName) {
    String qualifiedTableName = getQualifiedTableName();
    // Convert partition name format (pt=value) to spec format for SQL
    String partitionSpec = convertPartitionNameToSpec(partitionName);
    String dropPartitionSql =
        String.format(
            "ALTER TABLE %s DROP PARTITION (%s)",
            quoteTableName(qualifiedTableName), partitionSpec);

    try (Connection connection = getConnection(loadedTable.databaseName());
        Statement statement = connection.createStatement()) {
      LOG.info("Drop partition SQL: {}", dropPartitionSql);
      statement.execute(dropPartitionSql);
      return true;
    } catch (SQLException e) {
      GravitinoRuntimeException exception = exceptionConverter.toGravitinoException(e);
      if (isPartitionNotFoundError(exception)) {
        return false;
      }
      throw exception;
    }
  }

  /**
   * Checks if the exception indicates that a partition was not found.
   *
   * @param exception the exception to check
   * @return true if the exception indicates partition not found
   */
  private boolean isPartitionNotFoundError(GravitinoRuntimeException exception) {
    if (exception instanceof NoSuchPartitionException) {
      return true;
    }
    String message = exception.getMessage();
    if (message == null) {
      return false;
    }
    String lowerMessage = message.toLowerCase();
    return lowerMessage.contains("not found") || lowerMessage.contains("does not exist");
  }

  // ==================== Private Helper Methods ====================

  /**
   * Gets the fully qualified table name (project.schema.table).
   *
   * @return the three-layer qualified table name
   */
  private String getQualifiedTableName() {
    return projectName + "." + loadedTable.databaseName() + "." + loadedTable.name();
  }

  /**
   * Extracts partition field names from the table's partitioning transforms.
   *
   * @param partitioning the partitioning transforms
   * @return the partition field names as a 2D array
   */
  private String[][] extractPartitionFieldNames(Transform[] partitioning) {
    return Arrays.stream(partitioning)
        .filter(t -> t instanceof Transforms.IdentityTransform)
        .map(t -> ((Transforms.IdentityTransform) t).fieldName())
        .toArray(String[][]::new);
  }

  /**
   * Parses a partition specification string into a Partition object.
   *
   * <p>MaxCompute partition format: pt_col=value or pt_col1=value1/pt_col2=value2
   *
   * @param partitionSpec the partition specification string
   * @param fieldNames the expected field names
   * @return the parsed Partition, or null if parsing fails
   */
  private Partition parsePartitionSpec(String partitionSpec, String[][] fieldNames) {
    if (StringUtils.isBlank(partitionSpec)) {
      return null;
    }

    // Split by '/' for multi-level partitions
    String[] parts = partitionSpec.split("/");
    if (parts.length != fieldNames.length) {
      LOG.warn(
          "Partition spec {} has {} parts but expected {} fields",
          partitionSpec,
          parts.length,
          fieldNames.length);
      // Try to parse what we can
    }

    Literal<?>[] values = new Literal<?>[parts.length];
    String[][] actualFieldNames = new String[parts.length][];

    for (int i = 0; i < parts.length; i++) {
      String part = parts[i].trim();
      int eqIndex = part.indexOf('=');
      if (eqIndex <= 0) {
        LOG.warn("Invalid partition part: {}", part);
        return null;
      }

      String fieldName = part.substring(0, eqIndex);
      String value = part.substring(eqIndex + 1);

      // Remove quotes if present
      if ((value.startsWith("'") && value.endsWith("'"))
          || (value.startsWith("\"") && value.endsWith("\""))) {
        value = value.substring(1, value.length() - 1);
      }

      actualFieldNames[i] = new String[] {fieldName};
      values[i] = Literals.stringLiteral(value);
    }

    // Use the partition spec as the name
    return Partitions.identity(partitionSpec, actualFieldNames, values, Collections.emptyMap());
  }

  /**
   * Generates a partition specification string for SQL from an IdentityPartition.
   *
   * @param partition the identity partition
   * @return the partition specification string (e.g., "pt_col='value'")
   */
  private String generatePartitionSpec(IdentityPartition partition) {
    String[][] fieldNames = partition.fieldNames();
    Literal<?>[] values = partition.values();

    checkArgument(
        fieldNames.length == values.length,
        "Field names count (%s) must match values count (%s)",
        fieldNames.length,
        values.length);

    StringBuilder spec = new StringBuilder();
    for (int i = 0; i < fieldNames.length; i++) {
      if (i > 0) {
        spec.append(", ");
      }
      String fieldName = fieldNames[i][0];
      String value = formatLiteralValue(values[i]);
      spec.append(fieldName).append("=").append(value);
    }
    return spec.toString();
  }

  /**
   * Formats a literal value for use in SQL.
   *
   * @param literal the literal to format
   * @return the formatted value string
   */
  private String formatLiteralValue(Literal<?> literal) {
    if (literal == null || literal.value() == null || literal == Literals.NULL) {
      return "NULL";
    }

    Object value = literal.value();
    // String types need to be quoted
    if (literal.dataType() instanceof Types.StringType
        || literal.dataType() instanceof Types.VarCharType
        || literal.dataType() instanceof Types.FixedCharType) {
      return "'" + escapeSql(value.toString()) + "'";
    }

    return value.toString();
  }

  /**
   * Validates the partition specification against the table's partitioning.
   *
   * @param partition the partition to validate
   */
  private void validatePartitionSpec(IdentityPartition partition) {
    Transform[] partitioning = loadedTable.partitioning();
    if (ArrayUtils.isEmpty(partitioning)) {
      throw new IllegalArgumentException(
          String.format("Table %s is not partitioned", loadedTable.name()));
    }

    String[][] expectedFieldNames = extractPartitionFieldNames(partitioning);
    String[][] actualFieldNames = partition.fieldNames();

    if (expectedFieldNames.length != actualFieldNames.length) {
      throw new IllegalArgumentException(
          String.format(
              "Partition field count mismatch: expected %d, got %d",
              expectedFieldNames.length, actualFieldNames.length));
    }

    // Verify field names match
    for (int i = 0; i < expectedFieldNames.length; i++) {
      String expected = expectedFieldNames[i][0];
      String actual = actualFieldNames[i][0];
      if (!expected.equalsIgnoreCase(actual)) {
        throw new IllegalArgumentException(
            String.format(
                "Partition field name mismatch at position %d: expected '%s', got '%s'",
                i, expected, actual));
      }
    }
  }

  /**
   * Converts a partition name (pt=value/pt2=value2) to a SQL spec format (pt='value', pt2='value2')
   *
   * @param partitionName the partition name
   * @return the SQL spec format
   */
  private String convertPartitionNameToSpec(String partitionName) {
    if (StringUtils.isBlank(partitionName)) {
      return "";
    }

    String[] parts = partitionName.split("/");
    return Arrays.stream(parts)
        .map(
            part -> {
              int eqIndex = part.indexOf('=');
              // Defensive check: if no '=' found or '=' is at the start, return as-is
              // This handles malformed input gracefully
              if (eqIndex <= 0) {
                return part;
              }
              String fieldName = part.substring(0, eqIndex);
              String value = part.substring(eqIndex + 1);
              // Add quotes if not already present
              if (!value.startsWith("'") && !value.startsWith("\"")) {
                value = "'" + escapeSql(value) + "'";
              }
              return fieldName + "=" + value;
            })
        .collect(Collectors.joining(", "));
  }

  /**
   * Normalizes a partition name for comparison.
   *
   * @param partitionName the partition name
   * @return the normalized partition name
   */
  private String normalizePartitionName(String partitionName) {
    if (StringUtils.isBlank(partitionName)) {
      return "";
    }
    // Remove quotes and normalize separators
    return partitionName
        .replace("'", "")
        .replace("\"", "")
        .replace(", ", "/")
        .replace(",", "/")
        .toLowerCase();
  }
}
