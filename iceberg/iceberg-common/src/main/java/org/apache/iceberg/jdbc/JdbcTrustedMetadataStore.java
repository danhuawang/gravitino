/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity.TrustedMetadata;
import org.apache.gravitino.utils.jdbc.JdbcSqlScriptUtils;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Data access for the catalog-local trusted metadata rows of encrypted Iceberg JDBC tables.
 *
 * <p>The table is defined once by the versioned DDL scripts, which also declare the foreign key
 * from its identity columns to {@code iceberg_tables} with {@code ON UPDATE CASCADE ON DELETE
 * CASCADE}: upstream {@link JdbcCatalog} rename and drop move or remove the integrity row inside
 * the same autocommit statement, so this store has no rename or delete of its own. Every method
 * runs on the caller's connection and never commits or rolls it back; database errors propagate as
 * {@link SQLException}.
 */
final class JdbcTrustedMetadataStore {

  static final String TABLE_NAME = "iceberg_metadata_integrity";
  // The identity columns mirror the iceberg_tables primary key the DDL foreign key references.
  static final String CATALOG_NAME_COLUMN = JdbcUtil.CATALOG_NAME;
  static final String TABLE_NAMESPACE_COLUMN = JdbcUtil.TABLE_NAMESPACE;
  static final String TABLE_NAME_COLUMN = JdbcUtil.TABLE_NAME;
  static final String METADATA_LOCATION_COLUMN = "metadata_location";
  static final String TABLE_KEY_ID_COLUMN = "table_key_id";
  static final String HASH_ALGORITHM_COLUMN = "hash_algorithm";
  static final String METADATA_HASH_COLUMN = "metadata_hash";

  private static final List<String> IDENTITY_COLUMNS =
      List.of(CATALOG_NAME_COLUMN, TABLE_NAMESPACE_COLUMN, TABLE_NAME_COLUMN);
  private static final List<String> INTEGRITY_COLUMNS =
      List.of(
          METADATA_LOCATION_COLUMN,
          TABLE_KEY_ID_COLUMN,
          HASH_ALGORITHM_COLUMN,
          METADATA_HASH_COLUMN);
  private static final String POINTER_LOCATION_LABEL = "pointer_metadata_location";
  private static final String INTEGRITY_LOCATION_LABEL = "integrity_metadata_location";

  private static final String PROBE_SQL =
      String.format(
          "SELECT %s, %s FROM %s WHERE 1 = 0",
          columns("", IDENTITY_COLUMNS), columns("", INTEGRITY_COLUMNS), TABLE_NAME);
  private static final String INSERT_SQL =
      String.format(
          "INSERT INTO %s (%s, %s) VALUES (?, ?, ?, ?, ?, ?, ?)",
          TABLE_NAME, columns("", IDENTITY_COLUMNS), columns("", INTEGRITY_COLUMNS));
  private static final String UPDATE_SQL =
      String.format(
          "UPDATE %s SET %s WHERE %s AND %s",
          TABLE_NAME,
          bindings("", INTEGRITY_COLUMNS, ", "),
          bindings("", IDENTITY_COLUMNS, " AND "),
          bindings("", INTEGRITY_COLUMNS, " AND "));
  // The pointer row drives the read: integrity columns come back null for a plain table. Both
  // metadata_location columns are aliased so the mapper never depends on how a driver resolves a
  // label that two joined tables share.
  private static final String LOAD_SQL =
      String.format(
          "SELECT t.%s AS %s, i.%s AS %s, %s FROM %s t LEFT JOIN %s i ON %s WHERE %s",
          BaseMetastoreTableOperations.METADATA_LOCATION_PROP,
          POINTER_LOCATION_LABEL,
          METADATA_LOCATION_COLUMN,
          INTEGRITY_LOCATION_LABEL,
          columns(
              "i.",
              INTEGRITY_COLUMNS.stream()
                  .filter(column -> !column.equals(METADATA_LOCATION_COLUMN))
                  .collect(Collectors.toList())),
          JdbcUtil.CATALOG_TABLE_VIEW_NAME,
          TABLE_NAME,
          IDENTITY_COLUMNS.stream()
              .map(column -> "i." + column + " = t." + column)
              .collect(Collectors.joining(" AND ")),
          bindings("t.", IDENTITY_COLUMNS, " AND "));
  // V1 shares iceberg_tables with views; only a TABLE (or a pre-V1 untyped) row is a pointer.
  private static final String V1_TABLE_RECORD_FILTER =
      String.format(
          " AND (t.%s = '%s' OR t.%s IS NULL)",
          JdbcUtil.RECORD_TYPE, JdbcUtil.TABLE_RECORD_TYPE, JdbcUtil.RECORD_TYPE);

  private static final String POSTGRESQL_DATABASE_PRODUCT = "PostgreSQL";
  private static final String MYSQL_DATABASE_PRODUCT = "MySQL";
  private static final String SCHEMA_RESOURCE_FORMAT =
      "/org/apache/gravitino/iceberg/jdbc/metadata-integrity/%s/schema-v1.sql";

  private final String catalogName;
  private final JdbcUtil.SchemaVersion schemaVersion;

  JdbcTrustedMetadataStore(String catalogName, JdbcUtil.SchemaVersion schemaVersion) {
    Preconditions.checkArgument(
        catalogName != null && !catalogName.trim().isEmpty(), "catalogName cannot be blank");
    this.catalogName = catalogName;
    this.schemaVersion = Preconditions.checkNotNull(schemaVersion, "schemaVersion cannot be null");
  }

  /**
   * Runs the versioned {@code CREATE TABLE IF NOT EXISTS} script for the connected product
   * (PostgreSQL or MySQL; anything else fails). Re-running keeps rows. The script's foreign key
   * needs {@code iceberg_tables} first, so Iceberg's own catalog DDL must already have run.
   */
  void provision(Connection connection) throws SQLException {
    JdbcSqlScriptUtils.executeSqlScript(
        connection, readResource(supportedSchemaResource(connection)));
  }

  /**
   * Resolves the connected product's DDL resource, then selects zero rows of every column, so an
   * unsupported product, a missing table or a missing column fails at startup. The product gate is
   * the one {@link #provision} applies: a pre-created table on a product the DDL does not exist for
   * never passes the probe.
   */
  void probe(Connection connection) throws SQLException {
    supportedSchemaResource(connection);
    try (Statement statement = connection.createStatement();
        ResultSet ignored = statement.executeQuery(PROBE_SQL)) {
      // The result set is empty by construction; only the statement's success matters.
    }
  }

  /**
   * Reads the pointer row of a table with its integrity row when one exists; empty when {@code
   * iceberg_tables} has no row for the identity. Throws {@link MetadataIntegrityException} when the
   * integrity row uses an unsupported hash algorithm.
   */
  Optional<TableRecord> load(Connection connection, TableIdentifier identifier)
      throws SQLException {
    requireConnection(connection);
    TableIdentifier table = requireIdentifier(identifier);
    String sql =
        schemaVersion == JdbcUtil.SchemaVersion.V1 ? LOAD_SQL + V1_TABLE_RECORD_FILTER : LOAD_SQL;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bindIdentity(statement, 1, table);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Optional.of(record(resultSet, table)) : Optional.empty();
      }
    }
  }

  /**
   * Inserts the integrity row of a newly enrolled table and returns the affected rows. The database
   * rejects the insert when no pointer row exists (foreign key).
   */
  int insert(
      Connection connection,
      TableIdentifier identifier,
      String metadataLocation,
      TrustedMetadata trustedMetadata)
      throws SQLException {
    requireConnection(connection);
    TableIdentifier table = requireIdentifier(identifier);
    try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      int next = bindIdentity(statement, 1, table);
      bindIntegrity(statement, next, metadataLocation, trustedMetadata);
      return statement.executeUpdate();
    }
  }

  /**
   * Compare-and-set of the integrity row: writes the new location, key id and hash only while the
   * stored ones still equal the expected ones. Returns {@code 1} on success and {@code 0} on a
   * miss.
   */
  int update(
      Connection connection,
      TableIdentifier identifier,
      String expectedLocation,
      TrustedMetadata expectedTrusted,
      String newLocation,
      TrustedMetadata newTrusted)
      throws SQLException {
    requireConnection(connection);
    TableIdentifier table = requireIdentifier(identifier);
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
      int next = bindIntegrity(statement, 1, newLocation, newTrusted);
      next = bindIdentity(statement, next, table);
      bindIntegrity(statement, next, expectedLocation, expectedTrusted);
      return statement.executeUpdate();
    }
  }

  /** Binds the identity columns from {@code start} and returns the next parameter index. */
  private int bindIdentity(PreparedStatement statement, int start, TableIdentifier identifier)
      throws SQLException {
    return bind(
        statement,
        start,
        IDENTITY_COLUMNS,
        List.of(
            catalogName, JdbcUtil.namespaceToString(identifier.namespace()), identifier.name()));
  }

  /** Binds the integrity columns from {@code start} and returns the next parameter index. */
  private static int bindIntegrity(
      PreparedStatement statement, int start, String metadataLocation, TrustedMetadata trusted)
      throws SQLException {
    TrustedMetadata encrypted = requireEncrypted(trusted);
    return bind(
        statement,
        start,
        INTEGRITY_COLUMNS,
        List.of(
            requireLocation(metadataLocation),
            encrypted.keyId(),
            IcebergMetadataIntegrity.HASH_ALGORITHM,
            encrypted.metadataHash()));
  }

  /**
   * Binds one value per column, in column order, into the parameter slots starting at {@code
   * start}; zipping the two lists keeps the slots in step with the column list the SQL was built
   * from. Returns the next free slot.
   */
  private static int bind(
      PreparedStatement statement, int start, List<String> columns, List<String> values)
      throws SQLException {
    Preconditions.checkArgument(
        columns.size() == values.size(), "%s values for %s columns", values.size(), columns.size());
    for (int i = 0; i < columns.size(); i++) {
      statement.setString(start + i, values.get(i));
    }
    return start + columns.size();
  }

  private static TableRecord record(ResultSet resultSet, TableIdentifier identifier)
      throws SQLException {
    String pointer = resultSet.getString(POINTER_LOCATION_LABEL);
    String integrityLocation = resultSet.getString(INTEGRITY_LOCATION_LABEL);
    if (integrityLocation == null) {
      return new TableRecord(pointer, null, null, null);
    }
    String hashAlgorithm = resultSet.getString(HASH_ALGORITHM_COLUMN);
    if (!IcebergMetadataIntegrity.HASH_ALGORITHM.equals(hashAlgorithm)) {
      throw new MetadataIntegrityException(
          "Unsupported trusted metadata hash algorithm '%s' for table %s",
          hashAlgorithm, identifier);
    }
    return new TableRecord(
        pointer,
        integrityLocation,
        resultSet.getString(TABLE_KEY_ID_COLUMN),
        resultSet.getString(METADATA_HASH_COLUMN));
  }

  private static String columns(String qualifier, List<String> columns) {
    return columns.stream().map(column -> qualifier + column).collect(Collectors.joining(", "));
  }

  private static String bindings(String qualifier, List<String> columns, String separator) {
    return columns.stream()
        .map(column -> qualifier + column + " = ?")
        .collect(Collectors.joining(separator));
  }

  /** The DDL resource of the connected product; fails for any product but PostgreSQL and MySQL. */
  private static String supportedSchemaResource(Connection connection) throws SQLException {
    return schemaResource(requireConnection(connection).getMetaData().getDatabaseProductName());
  }

  private static String schemaResource(@Nullable String product) throws SQLException {
    if (POSTGRESQL_DATABASE_PRODUCT.equalsIgnoreCase(product)) {
      return String.format(SCHEMA_RESOURCE_FORMAT, "postgresql");
    }
    if (MYSQL_DATABASE_PRODUCT.equalsIgnoreCase(product)) {
      return String.format(SCHEMA_RESOURCE_FORMAT, "mysql");
    }
    throw new SQLException(
        String.format(
            "JDBC metadata integrity schema initialization does not support database product %s",
            product == null ? "<unknown>" : product));
  }

  private static String readResource(String resourcePath) throws SQLException {
    try (InputStream input = JdbcTrustedMetadataStore.class.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new SQLException(
            String.format("JDBC metadata integrity schema resource is missing: %s", resourcePath));
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SQLException(
          String.format("Failed to read JDBC metadata integrity schema resource: %s", resourcePath),
          e);
    }
  }

  private static Connection requireConnection(Connection connection) {
    return Preconditions.checkNotNull(connection, "connection cannot be null");
  }

  private static TableIdentifier requireIdentifier(TableIdentifier identifier) {
    return Preconditions.checkNotNull(identifier, "identifier cannot be null");
  }

  private static String requireLocation(String metadataLocation) {
    Preconditions.checkArgument(
        metadataLocation != null && !metadataLocation.trim().isEmpty(),
        "metadataLocation cannot be blank");
    return metadataLocation;
  }

  private static TrustedMetadata requireEncrypted(TrustedMetadata trustedMetadata) {
    TrustedMetadata trusted =
        Preconditions.checkNotNull(trustedMetadata, "trustedMetadata cannot be null");
    Preconditions.checkArgument(
        trusted.keyId() != null && !trusted.keyId().trim().isEmpty(),
        "table key id cannot be blank");
    IcebergMetadataIntegrity.decodeMetadataHash(trusted.metadataHash());
    return trusted;
  }

  /**
   * One table identity as the catalog database sees it: the {@code iceberg_tables} pointer plus the
   * integrity columns when the table is enrolled. Integrity columns absent means a plain table.
   */
  static final class TableRecord {
    private final String metadataLocation;
    @Nullable private final String integrityMetadataLocation;
    @Nullable private final String keyId;
    @Nullable private final String metadataHash;

    private TableRecord(
        String metadataLocation,
        @Nullable String integrityMetadataLocation,
        @Nullable String keyId,
        @Nullable String metadataHash) {
      this.metadataLocation = metadataLocation;
      this.integrityMetadataLocation = integrityMetadataLocation;
      this.keyId = keyId;
      this.metadataHash = metadataHash;
    }

    /** Metadata location of the {@code iceberg_tables} pointer row. */
    String metadataLocation() {
      return metadataLocation;
    }

    /** Whether an integrity row exists for the identity, i.e. the table is enrolled. */
    boolean encrypted() {
      return integrityMetadataLocation != null;
    }

    /** Metadata location the integrity row was written for, or {@code null} for a plain table. */
    @Nullable
    String integrityMetadataLocation() {
      return integrityMetadataLocation;
    }

    /** Recorded key id, or {@code null} for a plain table. */
    @Nullable
    String keyId() {
      return keyId;
    }

    /** Recorded metadata hash, or {@code null} for a plain table. */
    @Nullable
    String metadataHash() {
      return metadataHash;
    }

    /**
     * Key id and hash to verify the pointer's metadata against; empty for a plain table and when
     * the integrity row was written for a different location than the pointer now holds.
     */
    TrustedMetadata trustedMetadata() {
      if (!encrypted() || !Objects.equals(metadataLocation, integrityMetadataLocation)) {
        return TrustedMetadata.of(null, null);
      }
      return TrustedMetadata.of(keyId, metadataHash);
    }
  }
}
