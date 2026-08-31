/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery.converter;

import java.net.HttpURLConnection;
import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;

/**
 * Exception converter for BigQuery.
 *
 * <p>Converts BigQuery JDBC SQLException to appropriate Gravitino exceptions based on error
 * messages and error codes.
 */
public class BigQueryExceptionConverter extends JdbcExceptionConverter {

  // HTTP status code for Too Many Requests (not defined in HttpURLConnection)
  private static final int HTTP_TOO_MANY_REQUESTS = 429;

  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException se) {
    String errorMessage = se.getMessage();
    if (errorMessage == null) {
      return super.toGravitinoException(se);
    }

    String lowerMessage = errorMessage.toLowerCase();

    // Table not found (404)
    if (lowerMessage.contains("not found: table")
        || lowerMessage.contains("table not found")
        || (lowerMessage.contains(String.valueOf(HttpURLConnection.HTTP_NOT_FOUND))
            && lowerMessage.contains("table"))) {
      return new NoSuchTableException(se, "BigQuery table not found: %s", errorMessage);
    }

    // Dataset not found (404)
    if (lowerMessage.contains("not found: dataset")
        || lowerMessage.contains("dataset not found")
        || (lowerMessage.contains(String.valueOf(HttpURLConnection.HTTP_NOT_FOUND))
            && lowerMessage.contains("dataset"))) {
      return new NoSuchSchemaException(se, "BigQuery dataset not found: %s", errorMessage);
    }

    // Table already exists (409)
    if (lowerMessage.contains("already exists: table")
        || lowerMessage.contains("table already exists")
        || (lowerMessage.contains(String.valueOf(HttpURLConnection.HTTP_CONFLICT))
            && lowerMessage.contains("table"))) {
      return new TableAlreadyExistsException(se, "BigQuery table already exists: %s", errorMessage);
    }

    // Dataset already exists (409)
    if (lowerMessage.contains("already exists: dataset")
        || lowerMessage.contains("dataset already exists")
        || (lowerMessage.contains(String.valueOf(HttpURLConnection.HTTP_CONFLICT))
            && lowerMessage.contains("dataset"))) {
      return new SchemaAlreadyExistsException(
          se, "BigQuery dataset already exists: %s", errorMessage);
    }

    // Permission denied (403)
    if (lowerMessage.contains("permission denied")
        || lowerMessage.contains("access denied")
        || lowerMessage.contains(String.valueOf(HttpURLConnection.HTTP_FORBIDDEN))) {
      return new GravitinoRuntimeException(
          se, "Permission denied: %s. Please check your BigQuery IAM permissions.", errorMessage);
    }

    // Invalid argument (400)
    if (lowerMessage.contains("invalid")
        || lowerMessage.contains(String.valueOf(HttpURLConnection.HTTP_BAD_REQUEST))
        || lowerMessage.contains("bad request")) {
      return new GravitinoRuntimeException(se, "Invalid BigQuery operation: %s", errorMessage);
    }

    // Quota exceeded (429)
    if (lowerMessage.contains("quota exceeded")
        || lowerMessage.contains("rate limit")
        || lowerMessage.contains(String.valueOf(HTTP_TOO_MANY_REQUESTS))) {
      return new GravitinoRuntimeException(
          se, "BigQuery quota exceeded: %s. Please try again later.", errorMessage);
    }

    // Default to parent class handling
    return super.toGravitinoException(se);
  }
}
