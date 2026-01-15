package org.apache.gravitino.catalog.bigquery;

import static org.apache.gravitino.connector.PropertyEntry.stringImmutablePropertyEntry;
import static org.apache.gravitino.connector.PropertyEntry.stringOptionalPropertyEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.connector.BasePropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * BigQuery schema (dataset) properties metadata.
 *
 * <p>Defines BigQuery-specific dataset properties such as location, default table expiration, case
 * sensitivity, and labels. Some properties are immutable after dataset creation (location,
 * default_collation) while others can be modified (default_table_expiration_days,
 * is_case_insensitive, description, labels).
 */
public class BigQuerySchemaPropertiesMetadata extends BasePropertiesMetadata {

  // BigQuery Dataset specific property keys - Supported properties
  public static final String LOCATION = "location";
  public static final String DEFAULT_TABLE_EXPIRATION_DAYS = "default_table_expiration_days";
  public static final String IS_CASE_INSENSITIVE = "is_case_insensitive";
  public static final String DESCRIPTION = "description";
  public static final String LABELS = "labels";
  public static final String DEFAULT_COLLATION = "default_collation";
  public static final String DEFAULT_PARTITION_EXPIRATION_DAYS =
      "default_partition_expiration_days";
  public static final String STORAGE_BILLING_MODEL = "storage_billing_model";
  public static final String MAX_TIME_TRAVEL_HOURS = "max_time_travel_hours";
  public static final String DEFAULT_KMS_KEY_NAME = "default_kms_key_name";
  public static final String DEFAULT_ROUNDING_MODE = "default_rounding_mode";
  public static final String FRIENDLY_NAME = "friendly_name";
  public static final String FAILOVER_RESERVATION = "failover_reservation";
  public static final String IS_PRIMARY = "is_primary";
  public static final String PRIMARY_REPLICA = "primary_replica";
  public static final String TAGS = "tags";

  private static final Map<String, PropertyEntry<?>> PROPERTIES_METADATA =
      createPropertiesMetadata();

  /**
   * Creates the properties metadata map for BigQuery datasets.
   *
   * @return immutable map of property entries
   */
  private static Map<String, PropertyEntry<?>> createPropertiesMetadata() {
    Map<String, PropertyEntry<?>> map = new HashMap<>();

    // Dataset location (immutable after creation)
    map.put(
        LOCATION,
        stringImmutablePropertyEntry(
            LOCATION,
            "Dataset location (e.g., 'us', 'eu', 'asia-northeast1'). "
                + "Cannot be changed after dataset creation.",
            false,
            null,
            false,
            false));

    // Default table expiration days (mutable)
    map.put(
        DEFAULT_TABLE_EXPIRATION_DAYS,
        stringOptionalPropertyEntry(
            DEFAULT_TABLE_EXPIRATION_DAYS,
            "Default expiration time for tables in days. "
                + "Tables will be automatically deleted after this period.",
            false,
            null,
            false));

    // Default partition expiration days (mutable)
    map.put(
        DEFAULT_PARTITION_EXPIRATION_DAYS,
        stringOptionalPropertyEntry(
            DEFAULT_PARTITION_EXPIRATION_DAYS,
            "Default expiration time for table partitions in days. "
                + "Partitions will be automatically deleted after this period.",
            false,
            null,
            false));

    // Case insensitive setting (mutable)
    map.put(
        IS_CASE_INSENSITIVE,
        stringOptionalPropertyEntry(
            IS_CASE_INSENSITIVE,
            "Whether the dataset is case insensitive for table and column names (true/false)",
            false,
            null,
            false));

    // Dataset description (mutable)
    map.put(
        DESCRIPTION,
        stringOptionalPropertyEntry(
            DESCRIPTION, "Dataset description for documentation purposes", false, null, false));

    // Labels in JSON format (mutable)
    map.put(
        LABELS,
        stringOptionalPropertyEntry(
            LABELS,
            "Dataset labels in JSON array format: [{\"key\":\"value\"}]. "
                + "Used for organizing and filtering datasets.",
            false,
            null,
            false));

    // Default collation (immutable after creation)
    map.put(
        DEFAULT_COLLATION,
        stringImmutablePropertyEntry(
            DEFAULT_COLLATION,
            "Default collation for STRING columns (e.g., 'und:ci' for case-insensitive). "
                + "Cannot be changed after dataset creation.",
            false,
            null,
            false,
            false));

    // Storage billing model (mutable, with restrictions)
    map.put(
        STORAGE_BILLING_MODEL,
        stringOptionalPropertyEntry(
            STORAGE_BILLING_MODEL,
            "Storage billing model: 'LOGICAL' (default) or 'PHYSICAL'. "
                + "Once changed, must wait 14 days before changing again.",
            false,
            null,
            false));

    // Max time travel hours (mutable)
    map.put(
        MAX_TIME_TRAVEL_HOURS,
        stringOptionalPropertyEntry(
            MAX_TIME_TRAVEL_HOURS,
            "Time travel window in hours. Must be multiple of 24, between 48-168 hours. Default: 168",
            false,
            null,
            false));

    // Default KMS key name (mutable)
    map.put(
        DEFAULT_KMS_KEY_NAME,
        stringOptionalPropertyEntry(
            DEFAULT_KMS_KEY_NAME,
            "Default Cloud KMS key for encrypting table data in this dataset. "
                + "Format: projects/PROJECT/locations/LOCATION/keyRings/RING/cryptoKeys/KEY",
            false,
            null,
            false));

    // Default rounding mode (mutable)
    map.put(
        DEFAULT_ROUNDING_MODE,
        stringOptionalPropertyEntry(
            DEFAULT_ROUNDING_MODE,
            "Default rounding mode for numeric operations: 'ROUND_HALF_EVEN' or 'ROUND_HALF_AWAY_FROM_ZERO'",
            false,
            null,
            false));

    // Friendly name (mutable)
    map.put(
        FRIENDLY_NAME,
        stringOptionalPropertyEntry(
            FRIENDLY_NAME, "A descriptive name for the dataset", false, null, false));

    // Failover reservation (mutable)
    map.put(
        FAILOVER_RESERVATION,
        stringOptionalPropertyEntry(
            FAILOVER_RESERVATION,
            "Associates the dataset to a reservation in case of failover scenario",
            false,
            null,
            false));

    // Is primary replica (mutable, for cross-region replication)
    map.put(
        IS_PRIMARY,
        stringOptionalPropertyEntry(
            IS_PRIMARY,
            "Declares if the dataset is the primary replica (true/false). "
                + "Only applicable for cross-region replicated datasets.",
            false,
            null,
            false));

    // Primary replica name (mutable, for cross-region replication)
    map.put(
        PRIMARY_REPLICA,
        stringOptionalPropertyEntry(
            PRIMARY_REPLICA,
            "The replica name to set as the primary replica. "
                + "Only applicable for cross-region replicated datasets.",
            false,
            null,
            false));

    // IAM tags (mutable)
    map.put(
        TAGS,
        stringOptionalPropertyEntry(
            TAGS,
            "IAM tags in JSON array format: [{\"key\":\"value\"}]. "
                + "Requires IAM tag functionality to be enabled.",
            false,
            null,
            false));

    return Collections.unmodifiableMap(map);
  }

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return PROPERTIES_METADATA;
  }

  /**
   * Checks if a property is supported by Gravitino BigQuery catalog.
   *
   * @param propertyName the property name to check
   * @return true if supported, false otherwise
   */
  public static boolean isSupportedProperty(String propertyName) {
    return PROPERTIES_METADATA.containsKey(propertyName);
  }

  /**
   * Checks if a property is mutable (can be changed after dataset creation).
   *
   * @param propertyName the property name to check
   * @return true if the property can be modified after creation
   */
  public static boolean isMutableProperty(String propertyName) {
    PropertyEntry<?> entry = PROPERTIES_METADATA.get(propertyName);
    return entry != null && !entry.isImmutable();
  }
}
