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

package org.apache.gravitino.flink.connector.jdbc.sqlserver;

import org.apache.flink.table.catalog.AbstractCatalog;
import org.apache.flink.table.catalog.CatalogFunction;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.FunctionNotExistException;
import org.apache.flink.table.catalog.exceptions.PartitionNotExistException;
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

/** SQL Server JDBC catalog for the Gravitino Flink connector. */
public class GravitinoSqlServerJdbcCatalog extends GravitinoJdbcCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoSqlServerJdbcCatalog.class);

  /**
   * Creates a new {@link GravitinoSqlServerJdbcCatalog}.
   *
   * @param context the catalog factory context
   * @param defaultDatabase the default database (schema) name
   * @param schemaAndTablePropertiesConverter converter for schema and table properties
   * @param partitionConverter converter for partition specs
   */
  protected GravitinoSqlServerJdbcCatalog(
      CatalogFactory.Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    super(context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }

  @Override
  public void open() throws CatalogException {
    try {
      applyJdbcCredential(catalog(), getMutableOptions());
    } catch (NoSuchCatalogException e) {
      // Expected during CREATE CATALOG: open() runs before the catalog is stored in Gravitino,
      // so credentials already present in the user-provided options are used instead. Logged at
      // WARN (with the exception) rather than DEBUG because the same catch fires for any other
      // cause of "catalog not found" (e.g. concurrently dropped), which would otherwise silently
      // fall back to stale/user-supplied credentials with no visible trace in default log output.
      LOG.warn(
          "Catalog '{}' not found in Gravitino during open(); skipping credential injection and "
              + "falling back to configured credentials. This is expected only during "
              + "CREATE CATALOG, before the catalog is registered.",
          getName(),
          e);
    }
  }

  @Override
  public void close() {
    // No inner Flink JDBC catalog to close for metadata operations. Per-table JDBC connections
    // used for data access are opened and closed directly by Flink's JdbcDynamicTableFactory,
    // not by this catalog or the Gravitino server.
    LOG.debug("Closing GravitinoSqlServerJdbcCatalog '{}'.", getName());
  }

  @Override
  protected AbstractCatalog realCatalog() {
    throw new UnsupportedOperationException(
        "GravitinoSqlServerJdbcCatalog does not support partition operations "
            + "(no inner Flink JDBC catalog is used)");
  }

  @Override
  public CatalogFunction getFunction(ObjectPath functionPath)
      throws FunctionNotExistException, CatalogException {
    throw new FunctionNotExistException(getName(), functionPath);
  }

  @Override
  public CatalogTableStatistics getTableStatistics(ObjectPath tablePath)
      throws TableNotExistException, CatalogException {
    return CatalogTableStatistics.UNKNOWN;
  }

  @Override
  public CatalogColumnStatistics getTableColumnStatistics(ObjectPath tablePath)
      throws TableNotExistException, CatalogException {
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
}
