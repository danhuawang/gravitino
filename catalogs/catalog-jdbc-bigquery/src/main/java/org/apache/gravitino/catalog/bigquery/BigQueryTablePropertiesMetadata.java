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
package org.apache.gravitino.catalog.bigquery;

import static org.apache.gravitino.connector.PropertyEntry.stringOptionalPropertyEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcTablePropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * BigQuery table properties metadata.
 *
 * <p>Defines BigQuery-specific table properties such as partition expiration, clustering fields,
 * table expiration, and require partition filter. All properties are mutable and can be modified
 * after table creation using ALTER TABLE SET OPTIONS.
 */
public class BigQueryTablePropertiesMetadata extends JdbcTablePropertiesMetadata {

  // BigQuery table specific property keys
  public static final String PARTITION_EXPIRATION_DAYS = "partition_expiration_days";
  public static final String REQUIRE_PARTITION_FILTER = "require_partition_filter";
  public static final String CLUSTERING_FIELDS = "clustering_fields";
  public static final String EXPIRATION_TIMESTAMP = "expiration_timestamp";
  public static final String FRIENDLY_NAME = "friendly_name";
  public static final String DESCRIPTION = "description";
  public static final String LABELS = "labels";

  // Additional BigQuery table properties
  public static final String KMS_KEY_NAME = "kms_key_name";
  public static final String DEFAULT_ROUNDING_MODE = "default_rounding_mode";
  public static final String ENABLE_CHANGE_HISTORY = "enable_change_history";
  public static final String MAX_STALENESS = "max_staleness";
  public static final String ENABLE_FINE_GRAINED_MUTATIONS = "enable_fine_grained_mutations";

  // Managed table properties (Preview)
  public static final String STORAGE_URI = "storage_uri";
  public static final String FILE_FORMAT = "file_format";
  public static final String TABLE_FORMAT = "table_format";

  // IAM tags
  public static final String TAGS = "tags";

  private static final Map<String, PropertyEntry<?>> PROPERTIES_METADATA =
      createPropertiesMetadata();

  /**
   * Creates the properties metadata map for BigQuery tables.
   *
   * @return immutable map of property entries
   */
  private static Map<String, PropertyEntry<?>> createPropertiesMetadata() {
    Map<String, PropertyEntry<?>> map = new HashMap<>();

    // Table description
    map.put(
        DESCRIPTION,
        stringOptionalPropertyEntry(
            DESCRIPTION, "Table description for documentation purposes", false, null, false));

    // Friendly name
    map.put(
        FRIENDLY_NAME,
        stringOptionalPropertyEntry(
            FRIENDLY_NAME,
            "Friendly name for the table displayed in BigQuery UI",
            false,
            null,
            false));

    // Partition expiration days
    map.put(
        PARTITION_EXPIRATION_DAYS,
        stringOptionalPropertyEntry(
            PARTITION_EXPIRATION_DAYS,
            "Number of days after which partitions expire and are automatically deleted. "
                + "Only applies to partitioned tables. Supports fractional days (e.g., 30.5).",
            false,
            null,
            false));

    // Require partition filter
    map.put(
        REQUIRE_PARTITION_FILTER,
        stringOptionalPropertyEntry(
            REQUIRE_PARTITION_FILTER,
            "Whether queries on this table must include a partition filter (true/false). "
                + "Helps prevent accidentally querying large amounts of data. Only applies to partitioned tables.",
            false,
            null,
            false));

    // Clustering fields (comma-separated, max 4)
    map.put(
        CLUSTERING_FIELDS,
        stringOptionalPropertyEntry(
            CLUSTERING_FIELDS,
            "Comma-separated list of clustering field names (maximum 4 fields). "
                + "Clustering improves query performance for filtered and aggregated queries.",
            false,
            null,
            false));

    // Table expiration timestamp
    map.put(
        EXPIRATION_TIMESTAMP,
        stringOptionalPropertyEntry(
            EXPIRATION_TIMESTAMP,
            "Timestamp when the table expires and is automatically deleted (ISO 8601 format). "
                + "Example: '2024-12-31T23:59:59Z'",
            false,
            null,
            false));

    // Labels in JSON format
    map.put(
        LABELS,
        stringOptionalPropertyEntry(
            LABELS,
            "Table labels in JSON array format: [{\"key\":\"value\"}]. "
                + "Used for organizing and filtering tables.",
            false,
            null,
            false));

    // KMS key name for encryption
    map.put(
        KMS_KEY_NAME,
        stringOptionalPropertyEntry(
            KMS_KEY_NAME,
            "Cloud KMS key name for table encryption. "
                + "Format: projects/PROJECT_ID/locations/LOCATION/keyRings/KEYRING/cryptoKeys/KEY",
            false,
            null,
            false));

    // Default rounding mode for NUMERIC/BIGNUMERIC columns
    map.put(
        DEFAULT_ROUNDING_MODE,
        stringOptionalPropertyEntry(
            DEFAULT_ROUNDING_MODE,
            "Default rounding mode for NUMERIC and BIGNUMERIC columns. "
                + "Supported values: ROUND_HALF_AWAY_FROM_ZERO, ROUND_HALF_EVEN",
            false,
            null,
            false));

    // Enable change history (Preview)
    map.put(
        ENABLE_CHANGE_HISTORY,
        stringOptionalPropertyEntry(
            ENABLE_CHANGE_HISTORY,
            "Enable change history capture on the table for use with CHANGES function (true/false). "
                + "This is a preview feature and may incur additional costs.",
            false,
            null,
            false));

    // Max staleness for change data capture
    map.put(
        MAX_STALENESS,
        stringOptionalPropertyEntry(
            MAX_STALENESS,
            "Maximum interval behind current time for reading stale data. "
                + "Format: INTERVAL \"4:0:0\" HOUR TO SECOND",
            false,
            null,
            false));

    // Enable fine-grained mutations (Preview)
    map.put(
        ENABLE_FINE_GRAINED_MUTATIONS,
        stringOptionalPropertyEntry(
            ENABLE_FINE_GRAINED_MUTATIONS,
            "Enable fine-grained DML optimization on the table (true/false). "
                + "This is a preview feature.",
            false,
            null,
            false));

    // Managed table properties (Preview)
    map.put(
        STORAGE_URI,
        stringOptionalPropertyEntry(
            STORAGE_URI,
            "Fully qualified location prefix for external folder where data is stored. "
                + "Required for managed tables. Format: gs://BUCKET_DIRECTORY/TABLE_DIRECTORY/",
            false,
            null,
            false));

    map.put(
        FILE_FORMAT,
        stringOptionalPropertyEntry(
            FILE_FORMAT,
            "Open-source file format for table data storage. "
                + "Only PARQUET is supported for managed tables.",
            false,
            "PARQUET",
            false));

    map.put(
        TABLE_FORMAT,
        stringOptionalPropertyEntry(
            TABLE_FORMAT,
            "Open table format for metadata-only snapshots. "
                + "Only ICEBERG is supported for managed tables.",
            false,
            "ICEBERG",
            false));

    // IAM tags
    map.put(
        TAGS,
        stringOptionalPropertyEntry(
            TAGS,
            "IAM tags for the table in JSON array format: [{\"key\":\"value\"}]. "
                + "Key should be namespaced key name, value should be short name.",
            false,
            null,
            false));

    return Collections.unmodifiableMap(map);
  }

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return PROPERTIES_METADATA;
  }
}
