/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery.utils;

import java.util.Arrays;
import org.apache.commons.lang3.StringUtils;

/** Utility class for BigQuery catalog operations. */
public class BigQueryUtils {

  // Maximum number of clustering fields allowed by BigQuery
  private static final int MAX_CLUSTERING_FIELDS = 4;

  private BigQueryUtils() {
    // Utility class, prevent instantiation
  }

  /**
   * Validates clustering fields property value.
   *
   * <p>BigQuery supports a maximum of 4 clustering fields per table. This method validates that the
   * clustering fields string contains at most 4 comma-separated field names.
   *
   * @param clusteringFields comma-separated clustering field names
   * @throws IllegalArgumentException if more than 4 fields are specified or if all fields are empty
   */
  public static void validateClusteringFields(String clusteringFields) {
    if (clusteringFields == null || clusteringFields.trim().isEmpty()) {
      return; // Empty or null is valid (no clustering)
    }

    String[] fields = clusteringFields.split(",");
    long fieldCount = Arrays.stream(fields).filter(StringUtils::isNotBlank).count();

    if (fieldCount == 0) {
      throw new IllegalArgumentException(
          "Clustering fields cannot be empty. Either provide valid field names or remove the clustering_fields property.");
    }

    if (fieldCount > MAX_CLUSTERING_FIELDS) {
      throw new IllegalArgumentException(
          String.format(
              "BigQuery supports maximum %d clustering fields, but got %d fields: %s. "
                  + "Consider using partitioning or reducing the number of clustering fields.",
              MAX_CLUSTERING_FIELDS, fieldCount, clusteringFields));
    }
  }

  /**
   * Gets the maximum number of clustering fields supported by BigQuery.
   *
   * @return maximum clustering fields (4)
   */
  public static int getMaxClusteringFields() {
    return MAX_CLUSTERING_FIELDS;
  }
}
