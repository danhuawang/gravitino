/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.iceberg.jdbc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.iceberg.common.cache.SupportsMetadataLocation;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.MetastoreRegisterTableUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.jdbc.JdbcTrustedMetadataStore.TableRecord;
import org.apache.iceberg.jdbc.JdbcUtil.SchemaVersion;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iceberg JDBC catalog that keeps a catalog-local trusted metadata row for every encrypted table.
 *
 * <p>The catalog itself is initialization and wiring. At startup it provisions (with {@code
 * jdbc.init-catalog-tables=true}) or probes the integrity table through {@link
 * JdbcTrustedMetadataStore} on the real connection and records the one capability it exposes,
 * {@link #supportsAtomicMetadataIntegrity()}. When the store is not usable the catalog fails once
 * if governed encryption is configured, otherwise it runs in <em>plain upstream mode</em>: upstream
 * {@link JdbcTableOperations}, no store, no verification, capability {@code false}.
 *
 * <p>All catalog SQL belongs to upstream {@link JdbcCatalog} and to {@link
 * IntegrityJdbcTableOperations}/{@link JdbcTrustedMetadataStore}; this class carries none. Rename
 * and drop are upstream's own autocommit statements: the integrity row follows or disappears
 * through the {@code ON UPDATE CASCADE ON DELETE CASCADE} foreign key the DDL declares (see {@link
 * #renameTable} and {@link #dropTable}). The cache's location gate ({@link #metadataLocation}) is
 * answered from the pointer row without a file read, and so is existence ({@link #tableExists})
 * whenever a pointer row exists.
 */
// Use Iceberg package to reuse JdbcUtil related classes.
public class JdbcCatalogWithMetadataLocationSupport extends JdbcCatalog
    implements SupportsMetadataLocation {
  private static final Logger LOG =
      LoggerFactory.getLogger(JdbcCatalogWithMetadataLocationSupport.class);

  private final boolean initializeCatalogTablesDefault;
  private final boolean governedEncryptionConfigured;

  private String jdbcCatalogName;
  private JdbcClientPool jdbcConnections;
  private SchemaVersion jdbcSchemaVersion;
  // Null while the catalog runs in plain upstream mode.
  @Nullable private JdbcTrustedMetadataStore trustedMetadataStore;

  public JdbcCatalogWithMetadataLocationSupport(boolean initializeCatalogTables) {
    this(initializeCatalogTables, false);
  }

  /**
   * Creates a JDBC catalog with explicit governed-encryption readiness requirements.
   *
   * @param initializeCatalogTables whether Iceberg should initialize its catalog tables
   * @param governedEncryptionConfigured whether governed encryption is enabled for this catalog
   */
  public JdbcCatalogWithMetadataLocationSupport(
      boolean initializeCatalogTables, boolean governedEncryptionConfigured) {
    this(
        initializeCatalogTables,
        governedEncryptionConfigured,
        properties -> new JdbcClientPool(properties.get(CatalogProperties.URI), properties));
  }

  @VisibleForTesting
  JdbcCatalogWithMetadataLocationSupport(
      boolean initializeCatalogTables,
      boolean governedEncryptionConfigured,
      Function<Map<String, String>, JdbcClientPool> clientPoolBuilder) {
    this(null, clientPoolBuilder, initializeCatalogTables, governedEncryptionConfigured);
  }

  JdbcCatalogWithMetadataLocationSupport(
      Function<Map<String, String>, FileIO> ioBuilder,
      Function<Map<String, String>, JdbcClientPool> clientPoolBuilder,
      boolean initializeCatalogTables) {
    this(ioBuilder, clientPoolBuilder, initializeCatalogTables, false);
  }

  private JdbcCatalogWithMetadataLocationSupport(
      Function<Map<String, String>, FileIO> ioBuilder,
      Function<Map<String, String>, JdbcClientPool> clientPoolBuilder,
      boolean initializeCatalogTables,
      boolean governedEncryptionConfigured) {
    super(ioBuilder, clientPoolBuilder, initializeCatalogTables);
    this.initializeCatalogTablesDefault = initializeCatalogTables;
    this.governedEncryptionConfigured = governedEncryptionConfigured;
  }

  /**
   * Initializes upstream's catalog tables, then provisions or probes the integrity table on the
   * real connection. A governed catalog whose store is not usable throws once and closes what
   * upstream opened; a non-governed one logs one warning and runs in plain upstream mode.
   */
  @Override
  public void initialize(String name, Map<String, String> properties) {
    boolean initializeCatalogTables =
        PropertyUtil.propertyAsBoolean(
            properties, JdbcUtil.INIT_CATALOG_TABLES_PROPERTY, initializeCatalogTablesDefault);
    try {
      super.initialize(name, properties);
    } catch (RuntimeException e) {
      closePartiallyInitializedResources(e);
      throw e;
    }
    try {
      initializeTrustedMetadataStore(initializeCatalogTables);
    } catch (RuntimeException e) {
      closeAfterInitializationFailure(e);
      throw e;
    }
  }

  /**
   * Whether this catalog publishes the pointer and the trusted metadata row of an encrypted table
   * atomically and verifies encrypted tables on load. Computed once at initialization from the real
   * connection: {@code true} when the integrity table was provisioned or probed successfully and
   * the connection reports transaction support (plus InnoDB on MySQL); {@code false} in plain
   * upstream mode. Governance consumes it to decide whether encryption may be admitted here.
   *
   * @return true when the catalog is integrity-aware, false in plain upstream mode
   */
  public boolean supportsAtomicMetadataIntegrity() {
    return trustedMetadataStore != null;
  }

  /** {@inheritDoc} */
  @Override
  protected TableOperations newTableOps(TableIdentifier tableIdentifier) {
    TableOperations operations = super.newTableOps(tableIdentifier);
    if (trustedMetadataStore == null) {
      return operations;
    }
    return new IntegrityJdbcTableOperations(
        jdbcConnections,
        operations.io(),
        jdbcCatalogName,
        tableIdentifier,
        properties(),
        jdbcSchemaVersion,
        trustedMetadataStore);
  }

  /** {@inheritDoc} */
  @Override
  public String metadataLocation(TableIdentifier tableIdentifier) {
    Map<String, String> table;

    try {
      table =
          JdbcUtil.loadTable(jdbcSchemaVersion, jdbcConnections, jdbcCatalogName, tableIdentifier);
    } catch (Exception e) {
      return null;
    }

    return table.get(METADATA_LOCATION_PROP);
  }

  /**
   * Answers {@code true} from the catalog's pointer row when one exists, and otherwise defers to
   * Iceberg's default, which loads the table. The fast path is the rename and register gate:
   * upstream {@link JdbcCatalog#renameTable} and {@code BaseMetastoreCatalog#registerTable} consult
   * this method first, and the default answer would read and, for an encrypted table, verify the
   * metadata file, so no plain table could be renamed while its file is unreadable and an encrypted
   * one would be verified twice. The fallback preserves upstream's answers for everything the
   * pointer row does not cover, in particular metadata-table identifiers such as {@code
   * ns.table.snapshots}, which upstream resolves through the base table. A missing table reaches
   * the fallback and reports {@code false} without a file read, since its operations find no
   * pointer row to load from.
   *
   * @param identifier table identifier
   * @return true when a pointer row exists for the identifier or upstream can load it
   */
  @Override
  public boolean tableExists(TableIdentifier identifier) {
    if (identifier != null && isValidIdentifier(identifier) && pointerRowExists(identifier)) {
      return true;
    }
    return super.tableExists(identifier);
  }

  /**
   * Check if the JDBC catalog schema version supports view operations. View operations are
   * supported from V1 schema version onwards.
   *
   * @return true if the schema version supports view operations, false otherwise
   */
  public boolean supportsViewsWithSchemaVersion() {
    // V0 doesn't support views, only V1 and later versions do
    return jdbcSchemaVersion != null && jdbcSchemaVersion != SchemaVersion.V0;
  }

  /**
   * Registers metadata the caller already read, optionally overwriting an existing registration.
   * The object passed in is the object published: a new registration commits it (with its integrity
   * row when the catalog is integrity-aware), and an overwrite hands it to the atomic repoint. The
   * backend does not read the file again before publishing; the returned table answers from a
   * verifying load after publishing.
   *
   * @param identifier table identifier to register
   * @param targetMetadata metadata read by the caller; its file location is the one registered
   * @param overwrite whether to overwrite an existing table registration
   * @return the registered table
   */
  public Table registerTable(
      TableIdentifier identifier, TableMetadata targetMetadata, boolean overwrite) {
    return MetastoreRegisterTableUtils.registerTable(
        this,
        identifier,
        targetMetadata.metadataFileLocation(),
        overwrite,
        targetMetadata,
        this::repoint);
  }

  /**
   * Renames a table through upstream's single autocommit {@code UPDATE} of the pointer row; the
   * integrity row of an encrypted table follows through the foreign key's {@code ON UPDATE
   * CASCADE}, inside that same statement. Upstream opens no transaction, so the cascade is the
   * atomicity.
   *
   * <p>Before delegating, an encrypted table is loaded once through its verifying operations, so a
   * table whose integrity row or metadata cannot be verified, or whose store is unreachable, is not
   * renamed. The gate is the store's pointer-plus-integrity read: a plain table (no integrity row)
   * delegates directly, with no metadata file read. The pre-read and upstream's statement are two
   * statements; a tamper between them passes the rename and fails closed on the next load.
   */
  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    requireVerifiedIfEncrypted(from);
    super.renameTable(from, to);
  }

  /**
   * Drops a table through upstream's single autocommit {@code DELETE} of the pointer row; the
   * integrity row of an encrypted table disappears through the foreign key's {@code ON DELETE
   * CASCADE}, inside that same statement. Upstream opens no transaction, so the cascade is the
   * atomicity.
   *
   * <p>The pre-read gate of {@link #renameTable} applies: an encrypted table is verified before the
   * delete and a plain table delegates directly without a file read. With {@code purge} upstream
   * loads the metadata again through the verifying operations before deleting the files.
   */
  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    requireVerifiedIfEncrypted(identifier);
    return super.dropTable(identifier, purge);
  }

  /**
   * Fails when the connected database does not report transaction support, which the two-statement
   * create, update and overwrite commits of {@link IntegrityJdbcTableOperations} rely on.
   *
   * @param connection open connection to the catalog database
   * @throws SQLException when transactions are not supported
   */
  static void validateTransactionSupport(Connection connection) throws SQLException {
    if (!connection.getMetaData().supportsTransactions()) {
      throw new SQLException(
          "The JDBC database does not report transaction support required for atomic metadata "
              + "integrity");
    }
  }

  /**
   * Register-overwrite callback: repoints the table from the location the utility observed to the
   * admitted metadata. Integrity-aware catalogs hand the repoint to {@link
   * IntegrityJdbcTableOperations#overwrite}, after a verifying load of the current metadata; plain
   * upstream mode uses upstream's compare-and-set of the pointer.
   */
  private void repoint(
      TableIdentifier tableIdentifier,
      String oldMetadataLocation,
      String newMetadataLocation,
      TableMetadata targetMetadata) {
    if (trustedMetadataStore == null) {
      repointPlain(tableIdentifier, oldMetadataLocation, newMetadataLocation);
      return;
    }
    IntegrityJdbcTableOperations operations =
        (IntegrityJdbcTableOperations) newTableOps(tableIdentifier);
    TableMetadata currentMetadata = operations.current();
    if (currentMetadata == null) {
      throw new NoSuchTableException("Table does not exist: %s", tableIdentifier);
    }
    if (!Objects.equals(currentMetadata.metadataFileLocation(), oldMetadataLocation)) {
      throw new CommitFailedException(
          "Cannot overwrite table %s: metadata location changed from %s to %s",
          tableIdentifier, oldMetadataLocation, currentMetadata.metadataFileLocation());
    }
    operations.overwrite(currentMetadata, targetMetadata);
  }

  private void repointPlain(
      TableIdentifier tableIdentifier, String oldMetadataLocation, String newMetadataLocation) {
    try {
      int updatedRecords =
          JdbcUtil.updateTable(
              jdbcSchemaVersion,
              jdbcConnections,
              jdbcCatalogName,
              tableIdentifier,
              newMetadataLocation,
              oldMetadataLocation);

      if (updatedRecords == 1) {
        LOG.debug("Successfully committed to existing table: {}", tableIdentifier);
      } else {
        throw new CommitFailedException(
            "Failed to update table %s from catalog %s", tableIdentifier, jdbcCatalogName);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedInterruptedException(e, "Interrupted during commit");
    } catch (SQLException e) {
      throw new UncheckedSQLException(e, "Unknown failure");
    }
  }

  private boolean pointerRowExists(TableIdentifier identifier) {
    try {
      return !JdbcUtil.loadTable(jdbcSchemaVersion, jdbcConnections, jdbcCatalogName, identifier)
          .isEmpty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedInterruptedException(
          e, "Interrupted while checking table %s in catalog %s", identifier, jdbcCatalogName);
    } catch (SQLException e) {
      throw new UncheckedSQLException(
          e, "Failed to check table %s in catalog %s", identifier, jdbcCatalogName);
    }
  }

  /**
   * The pre-read gate of rename and drop: one store read decides whether the table is enrolled;
   * only then is it loaded through the verifying operations. Plain upstream mode has no store and
   * no gate.
   */
  private void requireVerifiedIfEncrypted(TableIdentifier tableIdentifier) {
    if (trustedMetadataStore == null) {
      return;
    }
    Optional<TableRecord> record;
    try {
      record =
          jdbcConnections.run(connection -> trustedMetadataStore.load(connection, tableIdentifier));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedInterruptedException(
          e, "Interrupted while loading table %s", tableIdentifier);
    } catch (SQLException e) {
      throw new UncheckedSQLException(
          e, "Failed to load table %s from catalog %s", tableIdentifier, jdbcCatalogName);
    }
    // No pointer row: upstream reports the missing table. No integrity row: plain table, no file
    // read.
    if (record.isPresent() && record.get().encrypted()) {
      newTableOps(tableIdentifier).current();
    }
  }

  private void initializeTrustedMetadataStore(boolean initializeCatalogTables) {
    loadFields();
    trustedMetadataStore = new JdbcTrustedMetadataStore(jdbcCatalogName, jdbcSchemaVersion);
    try {
      jdbcConnections.run(
          connection -> {
            if (initializeCatalogTables) {
              trustedMetadataStore.provision(connection);
            } else {
              trustedMetadataStore.probe(connection);
            }
            validateTransactionSupport(connection);
            JdbcMetadataIntegritySchema.validateAtomicStorage(connection);
            return null;
          });
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedInterruptedException(
          e, "Interrupted while initializing JDBC metadata integrity table");
    } catch (SQLException e) {
      // The store stays only when the real connection passed every check: a failed governed
      // initialization also leaves the capability false.
      trustedMetadataStore = null;
      if (governedEncryptionConfigured) {
        throw new UncheckedSQLException(
            e,
            "JDBC metadata integrity requires a PostgreSQL or MySQL database with transaction "
                + "support and the catalog-local table %s; enable catalog table initialization or "
                + "provision the table with the packaged script before starting catalog %s",
            JdbcTrustedMetadataStore.TABLE_NAME,
            jdbcCatalogName);
      }
      LOG.warn(
          "JDBC metadata integrity is unavailable for catalog {}: catalog-local table {} could "
              + "not be provisioned or probed. Governed encryption is not configured, so the "
              + "catalog runs in plain upstream mode: encrypted tables are not verified and the "
              + "catalog reports no atomic metadata integrity. Provision the table and restart "
              + "before enabling governed encryption.",
          jdbcCatalogName,
          JdbcTrustedMetadataStore.TABLE_NAME,
          e);
    }
  }

  private void loadFields() {
    try {
      this.jdbcCatalogName = (String) FieldUtils.readField(this, "catalogName", true);
      Preconditions.checkState(
          jdbcCatalogName != null, "Failed to get catalogName field from JDBC catalog");
      this.jdbcConnections = (JdbcClientPool) FieldUtils.readField(this, "connections", true);
      Preconditions.checkState(
          jdbcConnections != null, "Failed to get connections field from JDBC catalog");
      this.jdbcSchemaVersion =
          (JdbcUtil.SchemaVersion) FieldUtils.readField(this, "schemaVersion", true);
      Preconditions.checkState(
          jdbcSchemaVersion != null, "Failed to get schemaVersion field from JDBC catalog");
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Closes what a successful upstream initialize opened when this class's own initialization then
   * fails. Upstream has registered its resources by then, so its {@code close()} releases them.
   */
  private void closeAfterInitializationFailure(RuntimeException initializationException) {
    try {
      close();
    } catch (RuntimeException closeException) {
      initializationException.addSuppressed(closeException);
    }
  }

  private void closePartiallyInitializedResources(RuntimeException initializationException) {
    // JdbcCatalog creates these resources before updating the JDBC schema, but registers them with
    // its CloseableGroup only after the update succeeds. Its close() method therefore cannot clean
    // them up when initialization fails during the schema update.
    closePartiallyInitializedResource("connections", initializationException);
    closePartiallyInitializedResource("io", initializationException);
  }

  private void closePartiallyInitializedResource(
      String fieldName, RuntimeException initializationException) {
    try {
      Closeable resource = (Closeable) FieldUtils.readField(this, fieldName, true);
      if (resource != null) {
        resource.close();
      }
    } catch (Exception closeException) {
      initializationException.addSuppressed(closeException);
    }
  }
}
