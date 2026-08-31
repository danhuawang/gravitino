/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcTablePropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * Table properties metadata for MaxCompute.
 *
 * <p>MaxCompute supports the following table properties:
 *
 * <ul>
 *   <li>lifecycle: Table lifecycle in days (data will be automatically recycled after expiration)
 *   <li>transactional: Whether the table is a transactional table (Delta Table)
 *   <li>write.bucket.num: Number of buckets for Delta Table (default 16, range 1-4096)
 *   <li>acid.data.retain.hours: Time Travel data retention hours (default 24, range 24-168)
 * </ul>
 */
public class MaxComputeTablePropertiesMetadata extends JdbcTablePropertiesMetadata {

  /** Table lifecycle in days. */
  public static final String LIFECYCLE = "lifecycle";

  /** Whether the table is a transactional table (Delta Table). */
  public static final String TRANSACTIONAL = "transactional";

  /** Number of buckets for Delta Table. */
  public static final String WRITE_BUCKET_NUM = "write.bucket.num";

  /** Time Travel data retention hours. */
  public static final String ACID_DATA_RETAIN_HOURS = "acid.data.retain.hours";

  /** Default lifecycle value (no expiration). */
  public static final int DEFAULT_LIFECYCLE = -1;

  /** Default bucket number for Delta Table. */
  public static final int DEFAULT_WRITE_BUCKET_NUM = 16;

  /** Default Time Travel data retention hours. */
  public static final int DEFAULT_ACID_DATA_RETAIN_HOURS = 24;

  /** Minimum Time Travel data retention hours. */
  public static final int MIN_ACID_DATA_RETAIN_HOURS = 24;

  /** Maximum Time Travel data retention hours. */
  public static final int MAX_ACID_DATA_RETAIN_HOURS = 168;

  /** Maximum bucket number for Delta Table. */
  public static final int MAX_WRITE_BUCKET_NUM = 4096;

  private static final Map<String, PropertyEntry<?>> PROPERTIES_METADATA;

  static {
    List<PropertyEntry<?>> propertyEntries =
        ImmutableList.of(
            PropertyEntry.integerOptionalPropertyEntry(
                LIFECYCLE,
                "Table lifecycle in days. Data will be automatically recycled after expiration. "
                    + "Set to -1 for no expiration.",
                false /* immutable */,
                DEFAULT_LIFECYCLE,
                false /* hidden */),
            PropertyEntry.booleanPropertyEntry(
                TRANSACTIONAL,
                "Whether the table is a transactional table (Delta Table). "
                    + "Delta Table supports upsert, incremental query, and Time Travel.",
                false /* required */,
                false /* immutable */,
                false /* default value */,
                false /* hidden */,
                false /* reserved */),
            PropertyEntry.integerOptionalPropertyEntry(
                WRITE_BUCKET_NUM,
                "Number of buckets for Delta Table. Range: 1-4096. "
                    + "Affects write parallelism and data distribution.",
                false /* immutable */,
                DEFAULT_WRITE_BUCKET_NUM,
                false /* hidden */),
            PropertyEntry.integerOptionalPropertyEntry(
                ACID_DATA_RETAIN_HOURS,
                "Time Travel data retention hours. Range: 24-168. "
                    + "Determines how far back Time Travel queries can go.",
                false /* immutable */,
                DEFAULT_ACID_DATA_RETAIN_HOURS,
                false /* hidden */));

    PROPERTIES_METADATA = Maps.uniqueIndex(propertyEntries, PropertyEntry::getName);
  }

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return PROPERTIES_METADATA;
  }
}
