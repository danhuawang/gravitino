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
package org.apache.iceberg;

import com.google.common.base.Preconditions;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;

/**
 * Shared helper for {@link org.apache.iceberg.catalog.Catalog#registerTable(TableIdentifier,
 * String, boolean)} on {@link BaseMetastoreCatalog} implementations where Iceberg's default
 * implementation does not support {@code overwrite=true}.
 *
 * <p>Callers read and admit the metadata file once; the parsed object they pass in is the object
 * committed for a new registration or handed to the atomic overwrite, so the file is never re-read
 * between admission and publication.
 */
public class MetastoreRegisterTableUtils {

  /** Atomically overwrites the metastore metadata location during register overwrite. */
  public interface MetadataLocationOverwrite {
    /**
     * @param identifier table identifier
     * @param oldMetadataLocation current metadata file location before overwrite
     * @param newMetadataLocation metadata file location to register
     * @param targetMetadata metadata read from {@code newMetadataLocation}
     */
    void overwrite(
        TableIdentifier identifier,
        String oldMetadataLocation,
        String newMetadataLocation,
        TableMetadata targetMetadata);
  }

  /**
   * Registers a previously parsed metadata object, optionally overwriting an existing registration
   * by replacing the metastore metadata location.
   *
   * @param catalog metastore catalog that performs the registration
   * @param identifier table identifier to register
   * @param metadataFileLocation location from which {@code targetMetadata} was read
   * @param overwrite whether to overwrite an existing table registration
   * @param targetMetadata metadata object admitted by the caller
   * @param metadataLocationOverwrite backend-specific atomic metadata location overwrite
   * @return the registered table
   */
  public static Table registerTable(
      BaseMetastoreCatalog catalog,
      TableIdentifier identifier,
      String metadataFileLocation,
      boolean overwrite,
      TableMetadata targetMetadata,
      MetadataLocationOverwrite metadataLocationOverwrite) {
    Preconditions.checkArgument(
        identifier != null && catalog.isValidIdentifier(identifier),
        "Invalid identifier: %s",
        identifier);
    Preconditions.checkArgument(
        metadataFileLocation != null && !metadataFileLocation.isEmpty(),
        "Cannot register an empty metadata file location as a table");
    Preconditions.checkArgument(targetMetadata != null, "targetMetadata cannot be null");

    boolean exists = catalog.tableExists(identifier);
    if (exists && !overwrite) {
      throw new AlreadyExistsException("Table already exists: %s", identifier);
    }

    TableOperations ops = catalog.newTableOps(identifier);
    if (!exists) {
      ops.commit(null, targetMetadata);
    } else {
      Preconditions.checkArgument(
          ops instanceof BaseMetastoreTableOperations,
          "Cannot overwrite table %s: table operations are not metastore-backed",
          identifier);
      BaseMetastoreTableOperations metastoreOps = (BaseMetastoreTableOperations) ops;
      metastoreOps.refresh();
      TableMetadata base = metastoreOps.current();
      Preconditions.checkArgument(
          base != null, "Cannot overwrite table %s: current metadata is missing", identifier);
      metadataLocationOverwrite.overwrite(
          identifier, base.metadataFileLocation(), metadataFileLocation, targetMetadata);
      metastoreOps.refresh();
    }

    return new BaseTable(
        ops,
        BaseMetastoreCatalog.fullTableName(catalog.name(), identifier),
        catalog.metricsReporter());
  }

  private MetastoreRegisterTableUtils() {}
}
