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
package org.apache.gravitino.catalog.maxcompute.converter;

import com.google.common.annotations.VisibleForTesting;
import java.sql.SQLException;
import java.util.Arrays;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.UnauthorizedException;

/**
 * Exception converter for MaxCompute.
 *
 * <p>MaxCompute uses ODPS error codes in the format "ODPS-XXXXXXX". This converter handles common
 * error patterns including authentication failures, permission denied, and schema/table not found
 * errors.
 *
 * <p>Common ODPS error code patterns:
 *
 * <ul>
 *   <li>ODPS-0110011: Authorization exception (unauthorized)
 *   <li>ODPS-0030001: Authorization exception (permission denied)
 *   <li>ODPS-0420095: Access Denied
 *   <li>Authentication failures: Various patterns in error messages
 * </ul>
 */
public class MaxComputeExceptionConverter extends JdbcExceptionConverter {

  // ODPS error code patterns
  @VisibleForTesting static final String ODPS_AUTHORIZATION_EXCEPTION = "ODPS-0110011";

  @VisibleForTesting static final String ODPS_PERMISSION_DENIED = "ODPS-0030001";

  @VisibleForTesting static final String ODPS_ACCESS_DENIED = "ODPS-0420095";

  // Error message patterns for authentication failures
  @VisibleForTesting
  static final String[] AUTHENTICATION_FAILURE_PATTERNS = {
    "Access denied",
    "authentication failed",
    "Invalid AccessKeyId",
    "invalid access key",
    "Signature not match",
    "signature does not match",
    "InvalidAccessKeyId",
    "SignatureDoesNotMatch"
  };

  // Error message patterns for permission denied
  @VisibleForTesting
  static final String[] PERMISSION_DENIED_PATTERNS = {
    "Access Denied",
    "no privilege",
    "permission denied",
    "not authorized",
    "Authorization exception",
    "You are unauthorized"
  };

  // Error message patterns for schema not found
  @VisibleForTesting
  static final String[] SCHEMA_NOT_FOUND_PATTERNS = {
    "project not found",
    "Project not found",
    "schema not found",
    "Schema not found",
    "Unknown project",
    "unknown project",
    "does not exist"
  };

  // Error message patterns for table not found
  @VisibleForTesting
  static final String[] TABLE_NOT_FOUND_PATTERNS = {
    "table not found", "Table not found", "Unknown table", "unknown table"
  };

  // Error message patterns for connection failures
  @VisibleForTesting
  static final String[] CONNECTION_FAILURE_PATTERNS = {
    "Connection refused",
    "Connection timed out",
    "Unable to connect",
    "Network is unreachable",
    "No route to host",
    "Connection reset",
    "Socket timeout",
    "Read timed out"
  };

  /**
   * Converts a SQLException to a GravitinoRuntimeException.
   *
   * @param se the SQLException to convert
   * @return the corresponding GravitinoRuntimeException
   */
  @SuppressWarnings("FormatStringAnnotation")
  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException se) {
    String message = se.getMessage();

    // Check for authentication failures first (most critical)
    if (isAuthenticationFailure(message)) {
      return new UnauthorizedException(se, se.getMessage());
    }

    // Check for permission denied errors
    if (isPermissionDenied(message)) {
      return new UnauthorizedException(se, se.getMessage());
    }

    // Check for connection failures
    if (isConnectionFailure(message)) {
      return new ConnectionFailedException(se, se.getMessage());
    }

    // Check for schema not found errors
    if (isSchemaNotFound(message)) {
      return new NoSuchSchemaException(se, se.getMessage());
    }

    // Check for table not found errors
    if (isTableNotFound(message)) {
      return new NoSuchTableException(se, se.getMessage());
    }

    // Default: return generic runtime exception
    return new GravitinoRuntimeException(se, se.getMessage());
  }

  /**
   * Checks if the error message indicates an authentication failure.
   *
   * @param message the error message
   * @return true if the message indicates an authentication failure
   */
  @VisibleForTesting
  static boolean isAuthenticationFailure(String message) {
    if (StringUtils.isBlank(message)) {
      return false;
    }
    return Arrays.stream(AUTHENTICATION_FAILURE_PATTERNS).anyMatch(message::contains);
  }

  /**
   * Checks if the error message indicates a permission denied error.
   *
   * @param message the error message
   * @return true if the message indicates a permission denied error
   */
  @VisibleForTesting
  static boolean isPermissionDenied(String message) {
    if (StringUtils.isBlank(message)) {
      return false;
    }
    // Check for ODPS error codes
    if (message.contains(ODPS_AUTHORIZATION_EXCEPTION)
        || message.contains(ODPS_PERMISSION_DENIED)
        || message.contains(ODPS_ACCESS_DENIED)) {
      return true;
    }
    // Check for error message patterns
    return Arrays.stream(PERMISSION_DENIED_PATTERNS).anyMatch(message::contains);
  }

  /**
   * Checks if the error message indicates a schema not found error.
   *
   * @param message the error message
   * @return true if the message indicates a schema not found error
   */
  @VisibleForTesting
  static boolean isSchemaNotFound(String message) {
    if (StringUtils.isBlank(message)) {
      return false;
    }
    return Arrays.stream(SCHEMA_NOT_FOUND_PATTERNS).anyMatch(message::contains);
  }

  /**
   * Checks if the error message indicates a table not found error.
   *
   * @param message the error message
   * @return true if the message indicates a table not found error
   */
  @VisibleForTesting
  static boolean isTableNotFound(String message) {
    if (StringUtils.isBlank(message)) {
      return false;
    }
    return Arrays.stream(TABLE_NOT_FOUND_PATTERNS).anyMatch(message::contains);
  }

  /**
   * Checks if the error message indicates a connection failure.
   *
   * @param message the error message
   * @return true if the message indicates a connection failure
   */
  @VisibleForTesting
  static boolean isConnectionFailure(String message) {
    if (StringUtils.isBlank(message)) {
      return false;
    }
    return Arrays.stream(CONNECTION_FAILURE_PATTERNS).anyMatch(message::contains);
  }
}
