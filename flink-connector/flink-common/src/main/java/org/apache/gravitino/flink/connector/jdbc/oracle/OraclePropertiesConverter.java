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

package org.apache.gravitino.flink.connector.jdbc.oracle;

import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.flink.table.catalog.CommonCatalogOptions;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.util.Preconditions;
import org.apache.gravitino.flink.connector.CatalogPropertiesConverter;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactoryOptions;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConverter;

/**
 * Properties converter for the Oracle JDBC catalog. Oracle differs from MySQL/PostgreSQL in two
 * ways: (1) the connection URL is used as-is without stripping a database path suffix; (2) table
 * names must be schema-qualified in uppercase because Oracle stores schema names as usernames.
 */
public class OraclePropertiesConverter extends JdbcPropertiesConverter {

  public static final OraclePropertiesConverter INSTANCE = new OraclePropertiesConverter();

  private OraclePropertiesConverter() {}

  @Override
  public String defaultDriverName() {
    return "oracle.jdbc.OracleDriver";
  }

  @Override
  public String getFlinkCatalogType() {
    return GravitinoJdbcCatalogFactoryOptions.ORACLE_IDENTIFIER;
  }

  /**
   * Returns the Oracle JDBC URL unchanged. Unlike MySQL/PostgreSQL, Oracle URLs do not have a
   * database path suffix to strip (the service name is already part of the URL).
   *
   * <p>Inlines the {@link CatalogPropertiesConverter} default property-mapping logic because {@code
   * OraclePropertiesConverter} does not directly implement {@link CatalogPropertiesConverter} — it
   * inherits via {@link JdbcPropertiesConverter} — and Java only permits {@code
   * Interface.super.method()} from a direct implementor. Delegating to {@code
   * super.toFlinkCatalogProperties()} would call {@link
   * JdbcPropertiesConverter#toFlinkCatalogProperties}, which strips the database path from the URL;
   * Oracle URLs must be kept unchanged.
   */
  @Override
  public Map<String, String> toFlinkCatalogProperties(Map<String, String> gravitinoProperties) {
    // Replicate CatalogPropertiesConverter default: map known keys, strip flink.bypass.* prefix.
    Map<String, String> flinkCatalogProperties = Maps.newHashMap();
    gravitinoProperties.forEach(
        (key, value) -> {
          if (key.startsWith(CatalogPropertiesConverter.FLINK_PROPERTY_PREFIX)) {
            String strippedKey =
                key.substring(CatalogPropertiesConverter.FLINK_PROPERTY_PREFIX.length());
            flinkCatalogProperties.put(strippedKey, value);
          } else {
            String convertedKey = transformPropertyToFlinkCatalog(key);
            if (convertedKey != null) {
              flinkCatalogProperties.put(convertedKey, value);
            }
          }
        });
    flinkCatalogProperties.put(CommonCatalogOptions.CATALOG_TYPE.key(), getFlinkCatalogType());

    String gravitinoJdbcUrl = gravitinoProperties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL);
    Preconditions.checkArgument(
        gravitinoJdbcUrl != null,
        "Cannot create catalog properties: missing '"
            + JdbcPropertiesConstants.GRAVITINO_JDBC_URL
            + "'.");
    flinkCatalogProperties.put(JdbcPropertiesConstants.FLINK_JDBC_URL, gravitinoJdbcUrl);
    return flinkCatalogProperties;
  }

  /**
   * Builds table connector options for Oracle. The connection URL stays unchanged (Oracle schemas
   * are users, not URL path components) and the table name is schema-qualified in uppercase.
   */
  @Override
  public Map<String, String> toFlinkTableProperties(
      Map<String, String> flinkCatalogProperties,
      Map<String, String> gravitinoProperties,
      ObjectPath tablePath) {
    String jdbcUser = flinkCatalogProperties.get(JdbcPropertiesConstants.FLINK_JDBC_USER);
    String jdbcPassword = flinkCatalogProperties.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD);
    String jdbcUrl = flinkCatalogProperties.get(JdbcPropertiesConstants.FLINK_JDBC_URL);
    Preconditions.checkArgument(
        jdbcUser != null, JdbcPropertiesConstants.FLINK_JDBC_USER + " should not be null.");
    Preconditions.checkArgument(
        jdbcPassword != null, JdbcPropertiesConstants.FLINK_JDBC_PASSWORD + " should not be null.");
    Preconditions.checkArgument(
        jdbcUrl != null, JdbcPropertiesConstants.FLINK_JDBC_URL + " should not be null.");
    Map<String, String> tableOptions = new HashMap<>();
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_TABLE_DATABASE_URL, jdbcUrl);
    // In Oracle, unquoted identifiers are stored in uppercase. Uppercase both schema and table so
    // the JDBC SQL (which is sent unquoted) matches what Oracle has stored.
    String schema = tablePath.getDatabaseName().toUpperCase(Locale.ROOT);
    String table = tablePath.getObjectName().toUpperCase(Locale.ROOT);
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_TABLE_NAME, schema + "." + table);
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_USER, jdbcUser);
    tableOptions.put(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD, jdbcPassword);
    return tableOptions;
  }
}
