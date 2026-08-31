/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery.operation;

import com.datastrato.gravitino.catalog.bigquery.BigQueryClientPool;
import com.google.api.services.bigquery.Bigquery;

/**
 * Utility class for BigQuery operations.
 *
 * <p>Provides common helper methods for BigQuery API operations to reduce code duplication.
 */
public class BigQueryOperationUtils {

  /**
   * Gets BigQuery API client and project ID from the client pool.
   *
   * <p>This method validates that the client pool is available and returns the necessary components
   * for BigQuery API operations.
   *
   * @param clientPool the BigQuery client pool
   * @param operationName the name of the operation (for error messages)
   * @return BigQueryContext containing the client and project ID
   * @throws RuntimeException if client pool is not available
   */
  public static BigQueryContext getBigQueryContext(
      BigQueryClientPool clientPool, String operationName) {
    if (clientPool == null) {
      throw new RuntimeException(
          String.format(
              "BigQuery API client pool is not available. Cannot perform %s operation.",
              operationName));
    }

    Bigquery bigquery = clientPool.getClient();
    String projectId = clientPool.getProjectId();

    return new BigQueryContext(bigquery, projectId);
  }

  /**
   * Context object containing BigQuery API client and project ID.
   *
   * <p>This class encapsulates the BigQuery client and project ID to simplify method signatures.
   */
  public static class BigQueryContext {
    private final Bigquery bigquery;
    private final String projectId;

    public BigQueryContext(Bigquery bigquery, String projectId) {
      this.bigquery = bigquery;
      this.projectId = projectId;
    }

    public Bigquery getBigquery() {
      return bigquery;
    }

    public String getProjectId() {
      return projectId;
    }
  }

  /**
   * Escapes special characters in strings for BigQuery SQL.
   *
   * @param str the string to escape
   * @return the escaped string
   */
  public static String escapeString(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private BigQueryOperationUtils() {
    // Utility class, prevent instantiation
  }
}
