package org.apache.gravitino.catalog.bigquery;

import java.util.Set;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

public class BigQueryCatalogCapability implements Capability {
  /**
   * Regular expression explanation: ^[a-zA-Z0-9_]{1,1024}$
   *
   * <p>^ - Start of the string
   *
   * <p>[a-zA-Z0-9_]{1,1024} - Consist of 1 to 1024 characters of letters (both cases), digits, and
   * underscores
   *
   * <p>$ - End of the string
   *
   * <p>BigQuery dataset and table names must contain only letters (a-z, A-Z), numbers (0-9), or
   * underscores (_). Names must start with a letter or underscore.
   */
  public static final String BIGQUERY_NAME_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]{0,1023}$";

  /** Reserved dataset names in BigQuery that cannot be used for user-defined datasets. */
  private static final Set<String> BIGQUERY_RESERVED_DATASETS =
      Set.of("information_schema", "sys", "INFORMATION_SCHEMA", "SYS");

  /** Reserved table names in BigQuery that cannot be used for user-defined tables. */
  private static final Set<String> BIGQUERY_RESERVED_TABLES =
      Set.of("__TABLES__", "__TABLES_SUMMARY__", "__PARTITIONS__", "__PARTITIONS_SUMMARY__");

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    // Check basic naming pattern
    if (!name.matches(BIGQUERY_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format(
              "The %s name '%s' is illegal. BigQuery names must start with a letter or underscore, "
                  + "contain only letters, numbers, or underscores, and be 1-1024 characters long.",
              scope, name));
    }

    // Check reserved names
    if (scope == Scope.SCHEMA && BIGQUERY_RESERVED_DATASETS.contains(name)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is reserved and cannot be used.", scope, name));
    }

    if (scope == Scope.TABLE && BIGQUERY_RESERVED_TABLES.contains(name)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is reserved and cannot be used.", scope, name));
    }

    return CapabilityResult.SUPPORTED;
  }
}
