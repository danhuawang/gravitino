/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.flink.connector.jdbc.oracle;

import java.util.List;
import java.util.Locale;
import org.apache.flink.table.catalog.AbstractCatalog;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogFunction;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.TableChange;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.FunctionNotExistException;
import org.apache.flink.table.catalog.exceptions.PartitionNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.catalog.stats.CatalogColumnStatistics;
import org.apache.flink.table.catalog.stats.CatalogTableStatistics;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Oracle JDBC catalog for the Gravitino Flink connector.
 *
 * <p>Flink's built-in {@code JdbcCatalogFactory} does not support Oracle JDBC URLs, so this class
 * overrides {@link #open()} to skip inner catalog creation and relies entirely on Gravitino's
 * server-side metadata for all catalog operations.
 *
 * <p>All table-name operations are normalized to uppercase before being sent to Gravitino. Oracle
 * stores unquoted identifiers in uppercase, and the Flink JDBC connector sends unquoted SQL, so
 * table names must be uppercase for the two layers to resolve to the same physical object.
 */
public class GravitinoOracleJdbcCatalog extends GravitinoJdbcCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoOracleJdbcCatalog.class);

  protected GravitinoOracleJdbcCatalog(
      CatalogFactory.Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    super(context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }

  /**
   * Opens this catalog by injecting JDBC credentials from Gravitino if available. Skips inner Flink
   * JDBC catalog creation because Flink's built-in {@code JdbcCatalogFactory} does not support
   * Oracle URLs.
   */
  @Override
  public void open() throws CatalogException {
    try {
      applyJdbcCredential(catalog(), getMutableOptions());
      LOG.debug("Oracle JDBC catalog '{}' opened; credentials applied.", getName());
    } catch (NoSuchCatalogException e) {
      LOG.warn(
          "Catalog '{}' not found in Gravitino during open(); credential injection skipped."
              + " This is expected during CREATE CATALOG.",
          getName(),
          e);
    }
  }

  /** No-op: this catalog holds no inner Flink JDBC catalog resources to release. */
  @Override
  public void close() {}

  /**
   * Always throws {@link UnsupportedOperationException}: this catalog does not wrap an inner Flink
   * JDBC catalog. Methods that would delegate to the inner catalog (statistics, functions) are
   * individually overridden.
   */
  @Override
  protected AbstractCatalog realCatalog() {
    throw new UnsupportedOperationException(
        "GravitinoOracleJdbcCatalog does not use an inner Flink JDBC catalog");
  }

  // ---------------------------------------------------------------------------
  // Table-name normalization — uppercase all table names before delegation.
  // Oracle stores unquoted identifiers in uppercase; Flink JDBC sends unquoted
  // SQL, so both layers must agree on the same (uppercase) name.
  // ---------------------------------------------------------------------------

  @Override
  public CatalogBaseTable getTable(ObjectPath tablePath)
      throws TableNotExistException, CatalogException {
    return super.getTable(upperCaseTable(tablePath));
  }

  @Override
  public boolean tableExists(ObjectPath tablePath) throws CatalogException {
    return super.tableExists(upperCaseTable(tablePath));
  }

  @Override
  public void createTable(ObjectPath tablePath, CatalogBaseTable table, boolean ignoreIfExists)
      throws TableAlreadyExistException, DatabaseNotExistException, CatalogException {
    super.createTable(upperCaseTable(tablePath), table, ignoreIfExists);
  }

  @Override
  public void dropTable(ObjectPath tablePath, boolean ignoreIfNotExists)
      throws TableNotExistException, CatalogException {
    super.dropTable(upperCaseTable(tablePath), ignoreIfNotExists);
  }

  @Override
  public void renameTable(ObjectPath tablePath, String newTableName, boolean ignoreIfNotExists)
      throws TableNotExistException, TableAlreadyExistException, CatalogException {
    super.renameTable(
        upperCaseTable(tablePath), newTableName.toUpperCase(Locale.ROOT), ignoreIfNotExists);
  }

  @Override
  public void alterTable(ObjectPath tablePath, CatalogBaseTable newTable, boolean ignoreIfNotExists)
      throws TableNotExistException, CatalogException {
    super.alterTable(upperCaseTable(tablePath), newTable, ignoreIfNotExists);
  }

  @Override
  public void alterTable(
      ObjectPath tablePath,
      CatalogBaseTable newTable,
      List<TableChange> tableChanges,
      boolean ignoreIfNotExists)
      throws TableNotExistException, CatalogException {
    super.alterTable(upperCaseTable(tablePath), newTable, tableChanges, ignoreIfNotExists);
  }

  // ---------------------------------------------------------------------------
  // Functions — Oracle JDBC catalog does not expose user-defined functions.
  // Throw FunctionNotExistException rather than delegating to realCatalog(),
  // which is unavailable.
  // ---------------------------------------------------------------------------

  @Override
  public CatalogFunction getFunction(ObjectPath functionPath)
      throws FunctionNotExistException, CatalogException {
    throw new FunctionNotExistException(getName(), functionPath);
  }

  // ---------------------------------------------------------------------------
  // Statistics — no inner catalog; return UNKNOWN for query planning.
  // ---------------------------------------------------------------------------

  @Override
  public CatalogTableStatistics getTableStatistics(ObjectPath tablePath)
      throws TableNotExistException, CatalogException {
    if (!tableExists(tablePath)) {
      throw new TableNotExistException(getName(), tablePath);
    }
    return CatalogTableStatistics.UNKNOWN;
  }

  @Override
  public CatalogColumnStatistics getTableColumnStatistics(ObjectPath tablePath)
      throws TableNotExistException, CatalogException {
    if (!tableExists(tablePath)) {
      throw new TableNotExistException(getName(), tablePath);
    }
    return CatalogColumnStatistics.UNKNOWN;
  }

  @Override
  public CatalogTableStatistics getPartitionStatistics(
      ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
      throws PartitionNotExistException, CatalogException {
    return CatalogTableStatistics.UNKNOWN;
  }

  @Override
  public CatalogColumnStatistics getPartitionColumnStatistics(
      ObjectPath tablePath, CatalogPartitionSpec partitionSpec)
      throws PartitionNotExistException, CatalogException {
    return CatalogColumnStatistics.UNKNOWN;
  }

  /**
   * Returns a new {@link ObjectPath} with the table name converted to uppercase. The database name
   * is preserved as-is because it is already normalized upstream (Oracle schema = user, always
   * stored uppercase by Gravitino's {@code normalizeSchemaName}).
   */
  static ObjectPath upperCaseTable(ObjectPath path) {
    return new ObjectPath(path.getDatabaseName(), path.getObjectName().toUpperCase(Locale.ROOT));
  }
}
