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

package org.apache.gravitino.flink.connector.jdbc.sqlserver;

import java.util.HashMap;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.util.Preconditions;
import org.apache.gravitino.flink.connector.CatalogPropertiesConverter;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactoryOptions;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConverter;

/** Properties converter for the SQL Server JDBC catalog. */
public class SqlServerPropertiesConverter extends JdbcPropertiesConverter {

  /** Singleton instance of this converter. */
  public static final SqlServerPropertiesConverter INSTANCE = new SqlServerPropertiesConverter();

  private static final String DATABASE_NAME_PROPERTY = "databaseName";
  private static final String DATABASE_NAME_PREFIX = DATABASE_NAME_PROPERTY + "=";
  // "database" is a documented alias for "databaseName" in the MS SQL Server JDBC driver.
  private static final String DATABASE_ALIAS_PREFIX = "database=";

  private SqlServerPropertiesConverter() {}

  @Override
  public String defaultDriverName() {
    return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
  }

  @Override
  public String getFlinkCatalogType() {
    return GravitinoJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER;
  }

  @Override
  public Map<String, String> toGravitinoCatalogProperties(Configuration flinkConf) {
    Map<String, String> gravitinoProperties = super.toGravitinoCatalogProperties(flinkConf);
    // Validate required options early with a clear message. MySQL/PostgreSQL get this for free
    // from the inner JdbcCatalogFactory; SQL Server bypasses that path so we check here.
    // This method is only called from storeCatalog() (CREATE CATALOG), not on catalog load.
    Preconditions.checkArgument(
        gravitinoProperties.containsKey(JdbcPropertiesConstants.GRAVITINO_JDBC_USER),
        "Missing required options are:\n\nusername");
    Preconditions.checkArgument(
        gravitinoProperties.containsKey(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD),
        "Missing required options are:\n\npassword");
    String defaultDatabase = flinkConf.get(GravitinoJdbcCatalogFactoryOptions.DEFAULT_DATABASE);
    Preconditions.checkArgument(
        defaultDatabase != null,
        GravitinoJdbcCatalogFactoryOptions.DEFAULT_DATABASE.key() + " should not be null.");

    Map<String, String> rawConf = flinkConf.toMap();
    String baseUrl = rawConf.get(JdbcPropertiesConstants.FLINK_JDBC_URL);
    Preconditions.checkArgument(
        baseUrl != null, JdbcPropertiesConstants.FLINK_JDBC_URL + " should not be null.");
    String jdbcDatabase = rawConf.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE);
    if (jdbcDatabase == null) {
      jdbcDatabase = extractDatabaseName(baseUrl);
    }
    Preconditions.checkArgument(
        jdbcDatabase != null,
        "Cannot determine the SQL Server database name: either set '"
            + JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE
            + "' explicitly, or include 'databaseName=<db>' in '"
            + JdbcPropertiesConstants.FLINK_JDBC_URL
            + "'.");
    // super.toGravitinoCatalogProperties() already stored the unrecognized Flink 'jdbc-database'
    // option as a bypass property; remove it to avoid a duplicate alongside the first-class
    // property set below.
    gravitinoProperties.remove(
        CatalogPropertiesConverter.FLINK_PROPERTY_PREFIX
            + JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE);
    gravitinoProperties.put(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE, jdbcDatabase);
    gravitinoProperties.put(
        JdbcPropertiesConstants.GRAVITINO_JDBC_URL, buildJdbcUrl(baseUrl, jdbcDatabase));
    return gravitinoProperties;
  }

  @Override
  public Map<String, String> toFlinkCatalogProperties(Map<String, String> gravitinoProperties) {
    Map<String, String> flinkProperties = super.toFlinkCatalogProperties(gravitinoProperties);
    String jdbcUrl = gravitinoProperties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL);
    Preconditions.checkArgument(
        jdbcUrl != null,
        "Cannot create catalog properties: missing '"
            + JdbcPropertiesConstants.GRAVITINO_JDBC_URL
            + "'.");

    flinkProperties.put(JdbcPropertiesConstants.FLINK_JDBC_URL, removeDatabaseName(jdbcUrl));
    Preconditions.checkArgument(
        flinkProperties.containsKey(JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE),
        "Cannot create catalog properties: missing '"
            + JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE
            + "' (SQL Server default-database must be a schema name, e.g. 'dbo').");
    // Pass jdbc-database into Flink catalog properties so toFlinkTableProperties can build
    // the full JDBC URL when gravitino table properties lack jdbc-url.
    String jdbcDatabase = gravitinoProperties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE);
    Preconditions.checkArgument(
        jdbcDatabase != null,
        "Cannot create catalog properties: missing '"
            + JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE
            + "'.");
    flinkProperties.put(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE, jdbcDatabase);
    return flinkProperties;
  }

  @Override
  public Map<String, String> toFlinkTableProperties(
      Map<String, String> flinkCatalogProperties,
      Map<String, String> gravitinoProperties,
      ObjectPath tablePath) {
    String jdbcUser = flinkCatalogProperties.get(JdbcPropertiesConstants.FLINK_JDBC_USER);
    String jdbcPassword = flinkCatalogProperties.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD);
    String jdbcUrl = gravitinoProperties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL);
    if (jdbcUrl == null) {
      String baseUrl = flinkCatalogProperties.get(JdbcPropertiesConstants.FLINK_JDBC_URL);
      String jdbcDatabase =
          flinkCatalogProperties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE);
      Preconditions.checkArgument(
          baseUrl != null, JdbcPropertiesConstants.FLINK_JDBC_URL + " should not be null.");
      Preconditions.checkArgument(
          jdbcDatabase != null,
          JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE + " should not be null.");
      jdbcUrl = buildJdbcUrl(baseUrl, jdbcDatabase);
    }
    Preconditions.checkArgument(
        jdbcUser != null, JdbcPropertiesConstants.FLINK_JDBC_USER + " should not be null.");
    Preconditions.checkArgument(
        jdbcPassword != null, JdbcPropertiesConstants.FLINK_JDBC_PASSWORD + " should not be null.");

    Map<String, String> tableOptions = new HashMap<>();
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_TABLE_DATABASE_URL, jdbcUrl);
    tableOptions.put(
        JdbcPropertiesConstants.FLINK_JDBC_TABLE_NAME,
        tablePath.getDatabaseName() + "." + tablePath.getObjectName());
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_USER, jdbcUser);
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD, jdbcPassword);
    return tableOptions;
  }

  static String buildJdbcUrl(String baseUrl, String databaseName) {
    Preconditions.checkArgument(baseUrl != null, "baseUrl must not be null.");
    Preconditions.checkArgument(
        databaseName != null && !databaseName.isEmpty(), "databaseName must not be null or empty.");
    String urlWithoutDatabase = removeDatabaseName(baseUrl);
    String dbParam = DATABASE_NAME_PROPERTY + "=" + databaseName;
    int firstProperty = urlWithoutDatabase.indexOf(';');
    if (firstProperty < 0) {
      return urlWithoutDatabase + ";" + dbParam;
    }
    return urlWithoutDatabase.substring(0, firstProperty)
        + ";"
        + dbParam
        + urlWithoutDatabase.substring(firstProperty);
  }

  static String removeDatabaseName(String jdbcUrl) {
    String[] parts = jdbcUrl.split(";", -1);
    StringBuilder builder = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      if (!parts[i].regionMatches(true, 0, DATABASE_NAME_PREFIX, 0, DATABASE_NAME_PREFIX.length())
          && !parts[i].regionMatches(
              true, 0, DATABASE_ALIAS_PREFIX, 0, DATABASE_ALIAS_PREFIX.length())) {
        builder.append(';').append(parts[i]);
      }
    }
    return builder.toString();
  }

  private static String extractDatabaseName(String jdbcUrl) {
    String[] parts = jdbcUrl.split(";", -1);
    for (int i = 1; i < parts.length; i++) {
      if (parts[i].regionMatches(true, 0, DATABASE_NAME_PREFIX, 0, DATABASE_NAME_PREFIX.length())) {
        return parts[i].substring(DATABASE_NAME_PREFIX.length());
      }
      if (parts[i].regionMatches(
          true, 0, DATABASE_ALIAS_PREFIX, 0, DATABASE_ALIAS_PREFIX.length())) {
        return parts[i].substring(DATABASE_ALIAS_PREFIX.length());
      }
    }
    return null;
  }
}
