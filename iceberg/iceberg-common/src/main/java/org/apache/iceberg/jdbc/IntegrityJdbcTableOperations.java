/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity.TrustedMetadata;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.jdbc.JdbcMetadataIntegrityTransaction.SqlOperation;
import org.apache.iceberg.jdbc.JdbcTrustedMetadataStore.TableRecord;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one bounded fork of Iceberg's JDBC table operations: publishes the {@code iceberg_tables}
 * pointer and the trusted metadata row of an encrypted table in one database transaction, and
 * verifies the metadata file against that row before it becomes current.
 *
 * <p>Tracks {@code org.apache.iceberg.jdbc.JdbcTableOperations} and the private commit SQL of
 * {@code org.apache.iceberg.jdbc.JdbcUtil} from iceberg-core 1.11.0; re-verify both against the
 * upstream sources on every Iceberg upgrade. Upstream commits each pointer mutation through its own
 * pooled autocommit statement with no hook on the connection, so the two pointer statements ({@link
 * #insertPointer} and {@link #updatePointer}) are copied here to run on the transaction's
 * connection. Metadata file writing and caching stay upstream's.
 *
 * <p>Two create preconditions are copied from upstream as well, and only those two: the {@code
 * jdbc.strict-mode} namespace check and the V1 view-name clash check ({@link
 * #requireCreatePreconditions}). Upstream enforces them only inside its private {@code
 * JdbcTableOperations.createTable}, which this class's {@link #doCommit} replaces, so nothing else
 * on the create path would run them. Upstream's third check, table existence, is not copied: a name
 * clash with a table, including a lost create race, surfaces as {@link AlreadyExistsException} from
 * the pointer row's primary key, while a clash with a V1 view surfaces from the restored view
 * precondition.
 *
 * <p>Rename and drop are not forked: the integrity row's foreign key ({@code ON UPDATE CASCADE ON
 * DELETE CASCADE}) moves or removes it inside upstream's own statement. That relies on upstream
 * {@link JdbcCatalog#renameTable} being a single UPDATE of the pointer row's key columns, as it is
 * in iceberg-core 1.11.0; a rename implemented as delete-and-insert would cascade-delete the
 * integrity row instead of moving it. The contract test
 * TestJdbcIntegrityContract.testEncryptedTableRenameAndDropFailClosedWhenStoreIsUnavailable renames
 * an enrolled table on PostgreSQL and MySQL through upstream's rename and checks that the integrity
 * row moved with it, which guards this dependency.
 */
final class IntegrityJdbcTableOperations extends JdbcTableOperations {

  private static final Logger LOG = LoggerFactory.getLogger(IntegrityJdbcTableOperations.class);

  // Upstream keeps these private without accessors; the pointer statements and store calls below
  // need them, so the constructor keeps its own references to the same values.
  private final JdbcClientPool connections;
  private final String catalogName;
  private final TableIdentifier tableIdentifier;
  private final JdbcUtil.SchemaVersion schemaVersion;
  private final boolean strictMode;
  private final JdbcTrustedMetadataStore store;

  IntegrityJdbcTableOperations(
      JdbcClientPool connections,
      FileIO fileIO,
      String catalogName,
      TableIdentifier tableIdentifier,
      Map<String, String> catalogProperties,
      JdbcUtil.SchemaVersion schemaVersion,
      JdbcTrustedMetadataStore store) {
    super(connections, fileIO, catalogName, tableIdentifier, catalogProperties, schemaVersion);
    this.connections = connections;
    this.catalogName = catalogName;
    this.tableIdentifier = tableIdentifier;
    this.schemaVersion = schemaVersion;
    this.strictMode =
        PropertyUtil.propertyAsBoolean(catalogProperties, JdbcUtil.STRICT_MODE_PROPERTY, false);
    this.store = Preconditions.checkNotNull(store, "store cannot be null");
  }

  /**
   * Refreshes from the pointer row and verifies the metadata file against the trusted row before it
   * becomes current. Any failure leaves the operations marked stale, so the next {@link #current()}
   * reads again instead of serving metadata that did not verify.
   */
  @Override
  public void doRefresh() {
    try {
      refreshAndVerify();
    } catch (RuntimeException e) {
      requestRefresh();
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void doCommit(TableMetadata base, TableMetadata metadata) {
    boolean newTable = base == null;
    String newLocation = writeNewMetadataIfRequired(newTable, metadata);
    TrustedMetadata trusted = IcebergMetadataIntegrity.derive(metadata);
    if (newTable) {
      publishCreate(newLocation, trusted);
    } else {
      publish(
          "update", connection -> commitUpdate(connection, base, metadata, newLocation, trusted));
    }
  }

  /**
   * Repoints a registered table to an existing metadata file (register-overwrite) and moves the
   * integrity row with the pointer, in one transaction. Iceberg has no native overwrite and so no
   * native guard on this path: inside the transaction the current metadata is verified against the
   * stored row and the target must keep the same encryption key binding. Unlike a commit, an
   * overwrite never enrolls a table without an integrity row; it is refused as not enrolled.
   *
   * @param base the table's current metadata as loaded through these operations
   * @param target the metadata to publish, as read from its location by the caller
   * @throws CommitFailedException if the pointer no longer holds {@code base}'s location
   * @throws MetadataIntegrityException if {@code base} does not verify against the stored row
   * @throws IllegalArgumentException if {@code target} enrolls, rekeys or strips the table's key
   */
  void overwrite(TableMetadata base, TableMetadata target) {
    Preconditions.checkNotNull(base, "base cannot be null");
    Preconditions.checkNotNull(target, "target cannot be null");
    TrustedMetadata trusted = IcebergMetadataIntegrity.derive(target);
    publish(
        "overwrite",
        connection ->
            JdbcMetadataIntegrityTransaction.run(
                connection,
                "the catalog pointer and integrity row of " + tableIdentifier,
                transaction -> {
                  repoint(
                      transaction,
                      base,
                      target.metadataFileLocation(),
                      trusted,
                      false,
                      () ->
                          IcebergMetadataIntegrity.requireSameEncryptionKey(
                              tableIdentifier, base, target));
                  return null;
                }));
    requestRefresh();
  }

  private void refreshAndVerify() {
    TableRecord record;
    try {
      record = connections.run(connection -> store.load(connection, tableIdentifier).orElse(null));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedInterruptedException(e, "Interrupted during refresh");
    } catch (SQLException e) {
      throw new UncheckedSQLException(
          e, "Failed to get table %s from catalog %s", tableIdentifier, catalogName);
    }
    if (record == null) {
      if (currentMetadataLocation() != null) {
        throw new NoSuchTableException(
            "Failed to load table %s from catalog %s: dropped by another process",
            tableIdentifier, catalogName);
      }
      disableRefresh();
      return;
    }

    String newLocation = record.metadataLocation();
    Preconditions.checkState(
        newLocation != null, "Invalid table %s: metadata location is null", tableIdentifier);
    TrustedMetadata trusted = trustedMetadata(record);
    boolean locationChanged = !Objects.equals(newLocation, currentMetadataLocation());
    refreshFromMetadataLocation(
        newLocation,
        // A missing file or a failed verification does not get better by retrying.
        exception ->
            !(exception instanceof NotFoundException)
                && !(exception instanceof MetadataIntegrityException),
        20,
        location -> {
          TableMetadata metadata = TableMetadataParser.read(io(), location);
          if (missingIntegrityRow(record, metadata)) {
            LOG.warn(
                "Full metadata integrity check skipped because no integrity row was recorded for"
                    + " table {}.",
                tableIdentifier);
          } else {
            IcebergMetadataIntegrity.verify(tableIdentifier, trusted, metadata);
          }
          return metadata;
        });
    if (!locationChanged && !missingIntegrityRow(record, current())) {
      // The loader above only runs for a new location; the cached metadata still has to match the
      // row as it is now. A table without a row warned when that location was first read.
      IcebergMetadataIntegrity.verify(tableIdentifier, trusted, current());
    }
  }

  /**
   * An encrypted table whose metadata names a key but which has no integrity row, as native Hive
   * treats a table without a metadata hash: loaded with a warning, enrolled by its next commit. A
   * row written for another location is not this case; {@link #trustedMetadata} rejects it before.
   */
  private static boolean missingIntegrityRow(TableRecord record, TableMetadata metadata) {
    return !record.encrypted() && keyId(metadata) != null;
  }

  /**
   * Copy of the two checks that private upstream {@code JdbcTableOperations.createTable}
   * (iceberg-core 1.11.0) runs before inserting the pointer row, with upstream's messages. The
   * table-existence check that follows them upstream is left to the pointer row's primary key.
   */
  private void requireCreatePreconditions() {
    Namespace namespace = tableIdentifier.namespace();
    if (strictMode && !JdbcUtil.namespaceExists(catalogName, connections, namespace)) {
      throw new NoSuchNamespaceException(
          "Cannot create table %s in catalog %s. Namespace %s does not exist",
          tableIdentifier, catalogName, namespace);
    }

    if (schemaVersion == JdbcUtil.SchemaVersion.V1
        && JdbcUtil.viewExists(catalogName, connections, tableIdentifier)) {
      throw new AlreadyExistsException("View with same name already exists: %s", tableIdentifier);
    }
  }

  private void publishCreate(String location, TrustedMetadata trusted) {
    requireCreatePreconditions();
    publish("create", connection -> commitCreate(connection, location, trusted));
  }

  private Void commitCreate(Connection connection, String location, TrustedMetadata trusted)
      throws SQLException {
    return JdbcMetadataIntegrityTransaction.run(
        connection,
        "the catalog pointer and integrity row of " + tableIdentifier,
        transaction -> {
          // The pointer row goes first: the integrity row's foreign key references it.
          requireOneRow(insertPointer(transaction, location), "insert catalog pointer");
          if (isEncrypted(trusted)) {
            requireOneRow(
                store.insert(transaction, tableIdentifier, location, trusted),
                "insert trusted metadata");
          }
          return null;
        });
  }

  private Void commitUpdate(
      Connection connection,
      TableMetadata base,
      TableMetadata metadata,
      String newLocation,
      TrustedMetadata trusted)
      throws SQLException {
    return JdbcMetadataIntegrityTransaction.run(
        connection,
        "the catalog pointer and integrity row of " + tableIdentifier,
        transaction -> {
          repoint(
              transaction,
              base,
              newLocation,
              trusted,
              true,
              () -> validateTableKey(base, metadata));
          return null;
        });
  }

  /**
   * Moves the pointer from {@code base}'s location to {@code newLocation} and the integrity row
   * with it, each as a compare-and-set against the rows the transaction sees. {@code keyGuard} runs
   * once {@code base} has verified against the stored row and rejects a change of the key binding
   * between {@code base} and the new metadata, with the exception type its caller owes.
   *
   * <p>With {@code enrollIfMissing}, a table without an integrity row skips the verification of
   * {@code base}, which has nothing to verify against, and has its row inserted instead of updated.
   * Without it, such a table is refused as not enrolled.
   */
  private void repoint(
      Connection transaction,
      TableMetadata base,
      String newLocation,
      TrustedMetadata newTrusted,
      boolean enrollIfMissing,
      Runnable keyGuard)
      throws SQLException {
    String expectedLocation = base.metadataFileLocation();
    TableRecord record =
        store
            .load(transaction, tableIdentifier)
            .orElseThrow(
                () ->
                    new CommitFailedException(
                        "Cannot commit table %s: it was dropped by another process",
                        tableIdentifier));
    if (!Objects.equals(record.metadataLocation(), expectedLocation)) {
      throw new CommitFailedException(
          "Cannot commit table %s: metadata was updated by another process", tableIdentifier);
    }
    TrustedMetadata expectedTrusted = trustedMetadata(record);
    boolean enroll = enrollIfMissing && !record.encrypted();
    if (!enroll) {
      IcebergMetadataIntegrity.verify(tableIdentifier, expectedTrusted, base);
    }
    keyGuard.run();

    requireOneRow(
        updatePointer(transaction, expectedLocation, newLocation), "update catalog pointer");
    // Verified (or enrolling) base plus an unchanged key binding: the new metadata is encrypted
    // exactly when the base is, so a plain table never carries an integrity row past this point.
    if (!isEncrypted(newTrusted)) {
      return;
    }
    if (enroll) {
      requireOneRow(
          insertEnrolled(transaction, newLocation, newTrusted), "insert trusted metadata");
    } else {
      requireOneRow(
          store.update(
              transaction,
              tableIdentifier,
              expectedLocation,
              expectedTrusted,
              newLocation,
              newTrusted),
          "update trusted metadata");
    }
  }

  /**
   * Inserts the integrity row of a table its first commit enrolls. The pointer compare-and-set
   * already passed, so a concurrent enrolling commit cannot reach this insert; a primary-key
   * collision means the row was written by another process (a drop and register of the same table),
   * and the retryable commit failure makes the caller refresh and verify against that row.
   */
  private int insertEnrolled(Connection transaction, String location, TrustedMetadata trusted)
      throws SQLException {
    try {
      return store.insert(transaction, tableIdentifier, location, trusted);
    } catch (SQLException e) {
      if (JdbcUtil.isConstraintViolation(e)) {
        throw new CommitFailedException(
            e,
            "Cannot commit table %s: integrity row was written by another process",
            tableIdentifier);
      }
      throw e;
    }
  }

  /**
   * Runs one pointer mutation on a pooled connection without pool-level retry (a replayed
   * transaction could commit twice) and maps the JDBC failures callers rely on: a constraint
   * violation while the table has never been loaded means another writer created it first; every
   * other database failure is an {@link UncheckedSQLException}. A failure after the commit was
   * attempted arrives as {@link CommitStateUnknownException} from the transaction helper.
   */
  private void publish(String operation, SqlOperation<Void> mutation) {
    try {
      connections.run(mutation::run, false);
      LOG.debug("Committed {} of table {} in catalog {}", operation, tableIdentifier, catalogName);
    } catch (SQLException e) {
      if (JdbcUtil.isConstraintViolation(e) && currentMetadataLocation() == null) {
        throw new AlreadyExistsException(e, "Table already exists: %s", tableIdentifier);
      }
      throw new UncheckedSQLException(
          e, "Failed to %s table %s in catalog %s", operation, tableIdentifier, catalogName);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedInterruptedException(e, "Interrupted during commit");
    }
  }

  /** The record's trusted metadata, rejecting an integrity row written for another location. */
  private TrustedMetadata trustedMetadata(TableRecord record) {
    if (record.encrypted()
        && !Objects.equals(record.integrityMetadataLocation(), record.metadataLocation())) {
      throw new MetadataIntegrityException(
          "Trusted metadata pointer does not match table %s", tableIdentifier);
    }
    return record.trustedMetadata();
  }

  private void validateTableKey(TableMetadata base, TableMetadata metadata) {
    ValidationException.check(
        Objects.equals(keyId(base), keyId(metadata)),
        "Cannot change Iceberg table property '%s' after creation for table %s",
        TableProperties.ENCRYPTION_TABLE_KEY,
        tableIdentifier);
  }

  private void requireOneRow(int rowCount, String operation) {
    if (rowCount != 1) {
      throw new CommitFailedException(
          "Failed to %s for table %s in catalog %s", operation, tableIdentifier, catalogName);
    }
  }

  // Copy of JdbcUtil.V1_DO_COMMIT_CREATE_SQL / V0_DO_COMMIT_CREATE_SQL (iceberg-core 1.11.0).
  private int insertPointer(Connection connection, String metadataLocation) throws SQLException {
    String sql =
        schemaVersion == JdbcUtil.SchemaVersion.V1
            ? String.format(
                "INSERT INTO %s (%s, %s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, NULL, ?)",
                JdbcUtil.CATALOG_TABLE_VIEW_NAME,
                JdbcUtil.CATALOG_NAME,
                JdbcUtil.TABLE_NAMESPACE,
                JdbcUtil.TABLE_NAME,
                BaseMetastoreTableOperations.METADATA_LOCATION_PROP,
                BaseMetastoreTableOperations.PREVIOUS_METADATA_LOCATION_PROP,
                JdbcUtil.RECORD_TYPE)
            : String.format(
                "INSERT INTO %s (%s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, NULL)",
                JdbcUtil.CATALOG_TABLE_VIEW_NAME,
                JdbcUtil.CATALOG_NAME,
                JdbcUtil.TABLE_NAMESPACE,
                JdbcUtil.TABLE_NAME,
                BaseMetastoreTableOperations.METADATA_LOCATION_PROP,
                BaseMetastoreTableOperations.PREVIOUS_METADATA_LOCATION_PROP);
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, catalogName);
      statement.setString(2, JdbcUtil.namespaceToString(tableIdentifier.namespace()));
      statement.setString(3, tableIdentifier.name());
      statement.setString(4, metadataLocation);
      if (schemaVersion == JdbcUtil.SchemaVersion.V1) {
        statement.setString(5, JdbcUtil.TABLE_RECORD_TYPE);
      }
      return statement.executeUpdate();
    }
  }

  // Copy of JdbcUtil.V1_DO_COMMIT_TABLE_SQL / V0_DO_COMMIT_SQL (iceberg-core 1.11.0).
  private int updatePointer(
      Connection connection, String expectedMetadataLocation, String metadataLocation)
      throws SQLException {
    String recordFilter =
        schemaVersion == JdbcUtil.SchemaVersion.V1
            ? String.format(
                " AND (%s = '%s' OR %s IS NULL)",
                JdbcUtil.RECORD_TYPE, JdbcUtil.TABLE_RECORD_TYPE, JdbcUtil.RECORD_TYPE)
            : "";
    String sql =
        String.format(
                "UPDATE %s SET %s = ?, %s = ? WHERE %s = ? AND %s = ? AND %s = ? AND %s = ?",
                JdbcUtil.CATALOG_TABLE_VIEW_NAME,
                BaseMetastoreTableOperations.METADATA_LOCATION_PROP,
                BaseMetastoreTableOperations.PREVIOUS_METADATA_LOCATION_PROP,
                JdbcUtil.CATALOG_NAME,
                JdbcUtil.TABLE_NAMESPACE,
                JdbcUtil.TABLE_NAME,
                BaseMetastoreTableOperations.METADATA_LOCATION_PROP)
            + recordFilter;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, metadataLocation);
      statement.setString(2, expectedMetadataLocation);
      statement.setString(3, catalogName);
      statement.setString(4, JdbcUtil.namespaceToString(tableIdentifier.namespace()));
      statement.setString(5, tableIdentifier.name());
      statement.setString(6, expectedMetadataLocation);
      return statement.executeUpdate();
    }
  }

  private static String keyId(TableMetadata metadata) {
    return metadata.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
  }

  private static boolean isEncrypted(TrustedMetadata trusted) {
    return trusted.keyId() != null;
  }
}
