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
package org.apache.gravitino.catalog.maxcompute.utils;

/** Utility class for MaxCompute catalog operations. */
public final class MaxComputeUtils {

  private static final String BACK_QUOTE = "`";

  private MaxComputeUtils() {
    // Utility class, prevent instantiation
  }

  /**
   * Quotes an identifier with backticks.
   *
   * @param identifier the identifier to quote
   * @return the quoted identifier
   */
  public static String quoteIdentifier(String identifier) {
    return BACK_QUOTE + identifier + BACK_QUOTE;
  }

  /**
   * Quotes a table name properly for MaxCompute SQL.
   *
   * <p>Handles different formats:
   *
   * <ul>
   *   <li>"table" becomes "`table`"
   *   <li>"schema.table" becomes "schema.`table`"
   *   <li>"project.schema.table" becomes "project.schema.`table`"
   * </ul>
   *
   * <p>Note: MaxCompute JDBC driver has issues parsing fully quoted three-layer names, so we only
   * quote the table name part. Schema and project names are typically system-controlled and don't
   * contain special characters.
   *
   * @param tableName the table name, possibly qualified with schema or project. schema
   * @return the properly quoted table name
   */
  public static String quoteTableName(String tableName) {
    if (tableName == null) {
      return "";
    }
    if (tableName.contains(".")) {
      // Only quote the last part (table name) to avoid JDBC driver parsing issues
      String[] parts = tableName.split("\\.");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < parts.length - 1; i++) {
        if (i > 0) {
          sb.append(".");
        }
        sb.append(parts[i]);
      }
      sb.append(".").append(quoteIdentifier(parts[parts.length - 1]));
      return sb.toString();
    }
    return quoteIdentifier(tableName);
  }

  /**
   * Escapes special characters in a string for SQL.
   *
   * <p>Uses SQL-standard escaping: single quotes are escaped by doubling them. This method can be
   * used for both comments and values.
   *
   * @param str the string to escape (comment or value)
   * @return the escaped string
   */
  public static String escapeSql(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("'", "''");
  }
}
