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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.catalog.CommonCatalogOptions;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactoryOptions;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests for {@link SqlServerPropertiesConverter}. */
public class TestSqlServerPropertiesConverter {

  private static final String USERNAME = "sa";
  private static final String PASSWORD = "YourStrong!Passw0rd";
  private static final String DATABASE = "test_db";
  private static final String SCHEMA = "dbo";
  private static final String BASE_URL =
      "jdbc:sqlserver://localhost:1433;encrypt=true;trustServerCertificate=true";
  private static final String JDBC_URL =
      "jdbc:sqlserver://localhost:1433;databaseName=test_db;encrypt=true;trustServerCertificate=true";
  private static final String FLINK_BYPASS_DEFAULT_DATABASE = "flink.bypass.default-database";

  @Test
  public void testToGravitinoCatalogProperties() {
    Configuration configuration =
        Configuration.fromMap(
            ImmutableMap.of(
                JdbcPropertiesConstants.FLINK_JDBC_USER,
                USERNAME,
                JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
                PASSWORD,
                JdbcPropertiesConstants.FLINK_JDBC_URL,
                BASE_URL,
                JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE,
                DATABASE,
                JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE,
                SCHEMA));

    Map<String, String> properties =
        SqlServerPropertiesConverter.INSTANCE.toGravitinoCatalogProperties(configuration);

    Assertions.assertEquals(USERNAME, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_USER));
    Assertions.assertEquals(
        PASSWORD, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD));
    Assertions.assertEquals(JDBC_URL, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL));
    Assertions.assertEquals(
        DATABASE, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE));
    Assertions.assertEquals(SCHEMA, properties.get(FLINK_BYPASS_DEFAULT_DATABASE));
    Assertions.assertEquals(
        "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DRIVER));
  }

  @Test
  public void testToGravitinoCatalogPropertiesWithExistingDatabaseName() {
    Configuration configuration =
        Configuration.fromMap(
            ImmutableMap.of(
                JdbcPropertiesConstants.FLINK_JDBC_USER,
                USERNAME,
                JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
                PASSWORD,
                JdbcPropertiesConstants.FLINK_JDBC_URL,
                "jdbc:sqlserver://localhost:1433;databaseName=old_db;encrypt=true",
                JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE,
                DATABASE,
                JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE,
                SCHEMA));

    Map<String, String> properties =
        SqlServerPropertiesConverter.INSTANCE.toGravitinoCatalogProperties(configuration);

    Assertions.assertEquals(
        "jdbc:sqlserver://localhost:1433;databaseName=test_db;encrypt=true",
        properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL));
  }

  @Test
  public void testToFlinkCatalogProperties() {
    Map<String, String> gravitinoProperties =
        ImmutableMap.of(
            JdbcPropertiesConstants.GRAVITINO_JDBC_USER,
            USERNAME,
            JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD,
            PASSWORD,
            JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
            JDBC_URL,
            JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE,
            DATABASE,
            FLINK_BYPASS_DEFAULT_DATABASE,
            SCHEMA);

    Map<String, String> properties =
        SqlServerPropertiesConverter.INSTANCE.toFlinkCatalogProperties(gravitinoProperties);

    Assertions.assertEquals(USERNAME, properties.get(JdbcPropertiesConstants.FLINK_JDBC_USER));
    Assertions.assertEquals(PASSWORD, properties.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD));
    Assertions.assertEquals(BASE_URL, properties.get(JdbcPropertiesConstants.FLINK_JDBC_URL));
    Assertions.assertEquals(
        SCHEMA, properties.get(JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE));
    Assertions.assertEquals(
        GravitinoJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER,
        properties.get(CommonCatalogOptions.CATALOG_TYPE.key()));
    Assertions.assertEquals(
        DATABASE, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE));
  }

  @Test
  public void testToFlinkTableProperties() {
    Map<String, String> flinkCatalogProperties =
        ImmutableMap.of(
            JdbcPropertiesConstants.FLINK_JDBC_USER,
            USERNAME,
            JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
            PASSWORD);
    Map<String, String> gravitinoProperties =
        ImmutableMap.of(JdbcPropertiesConstants.GRAVITINO_JDBC_URL, JDBC_URL);

    Map<String, String> properties =
        SqlServerPropertiesConverter.INSTANCE.toFlinkTableProperties(
            flinkCatalogProperties, gravitinoProperties, new ObjectPath("dbo", "test_table"));

    Assertions.assertEquals(
        JDBC_URL, properties.get(JdbcPropertiesConstants.FLINK_JDBC_TABLE_DATABASE_URL));
    Assertions.assertEquals(
        "dbo.test_table", properties.get(JdbcPropertiesConstants.FLINK_JDBC_TABLE_NAME));
    Assertions.assertEquals(USERNAME, properties.get(JdbcPropertiesConstants.FLINK_JDBC_USER));
    Assertions.assertEquals(PASSWORD, properties.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD));
  }

  @Test
  public void testToGravitinoCatalogPropertiesExtractsDatabaseFromUrl() {
    // No jdbc-database key; database should be extracted from databaseName= in URL.
    Configuration configuration =
        Configuration.fromMap(
            ImmutableMap.of(
                JdbcPropertiesConstants.FLINK_JDBC_USER,
                USERNAME,
                JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
                PASSWORD,
                JdbcPropertiesConstants.FLINK_JDBC_URL,
                JDBC_URL,
                JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE,
                SCHEMA));

    Map<String, String> properties =
        SqlServerPropertiesConverter.INSTANCE.toGravitinoCatalogProperties(configuration);

    Assertions.assertEquals(
        DATABASE, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE));
    Assertions.assertEquals(JDBC_URL, properties.get(JdbcPropertiesConstants.GRAVITINO_JDBC_URL));
  }

  @Test
  public void testToGravitinoCatalogPropertiesWithoutDatabaseThrows() {
    // Neither jdbc-database key nor databaseName= in URL: should throw.
    Configuration configuration =
        Configuration.fromMap(
            ImmutableMap.of(
                JdbcPropertiesConstants.FLINK_JDBC_USER,
                USERNAME,
                JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
                PASSWORD,
                JdbcPropertiesConstants.FLINK_JDBC_URL,
                BASE_URL,
                JdbcPropertiesConstants.FLINK_JDBC_DEFAULT_DATABASE,
                SCHEMA));

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                SqlServerPropertiesConverter.INSTANCE.toGravitinoCatalogProperties(configuration));
    Assertions.assertTrue(
        exception.getMessage().contains(JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE));
  }

  @Test
  public void testToFlinkTablePropertiesFromFlinkCatalogProperties() {
    // When gravitinoProperties lacks jdbc-url, URL is built from flinkCatalogProperties.
    Map<String, String> flinkCatalogProperties =
        ImmutableMap.of(
            JdbcPropertiesConstants.FLINK_JDBC_USER,
            USERNAME,
            JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
            PASSWORD,
            JdbcPropertiesConstants.FLINK_JDBC_URL,
            BASE_URL,
            JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE,
            DATABASE);

    Map<String, String> properties =
        SqlServerPropertiesConverter.INSTANCE.toFlinkTableProperties(
            flinkCatalogProperties, ImmutableMap.of(), new ObjectPath("dbo", "test_table"));

    Assertions.assertEquals(
        JDBC_URL, properties.get(JdbcPropertiesConstants.FLINK_JDBC_TABLE_DATABASE_URL));
    Assertions.assertEquals(
        "dbo.test_table", properties.get(JdbcPropertiesConstants.FLINK_JDBC_TABLE_NAME));
    Assertions.assertEquals(USERNAME, properties.get(JdbcPropertiesConstants.FLINK_JDBC_USER));
    Assertions.assertEquals(PASSWORD, properties.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD));
  }

  @Test
  public void testBuildJdbcUrlWithNoSemicolons() {
    String result =
        SqlServerPropertiesConverter.buildJdbcUrl("jdbc:sqlserver://localhost:1433", "mydb");
    Assertions.assertEquals("jdbc:sqlserver://localhost:1433;databaseName=mydb", result);
  }

  @Test
  public void testToGravitinoCatalogPropertiesWithoutDefaultDatabase() {
    Configuration configuration =
        Configuration.fromMap(
            ImmutableMap.of(
                JdbcPropertiesConstants.FLINK_JDBC_USER,
                USERNAME,
                JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
                PASSWORD,
                JdbcPropertiesConstants.FLINK_JDBC_URL,
                BASE_URL));

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                SqlServerPropertiesConverter.INSTANCE.toGravitinoCatalogProperties(configuration));
    Assertions.assertTrue(exception.getMessage().contains("default-database"));
  }

  @Test
  public void testToFlinkCatalogPropertiesWithoutDefaultDatabaseThrows() {
    // flink.bypass.default-database missing: must fail rather than silently use jdbc-database.
    Map<String, String> gravitinoProperties =
        ImmutableMap.of(
            JdbcPropertiesConstants.GRAVITINO_JDBC_USER,
            USERNAME,
            JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD,
            PASSWORD,
            JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
            JDBC_URL,
            JdbcPropertiesConstants.GRAVITINO_JDBC_DATABASE,
            DATABASE);

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                SqlServerPropertiesConverter.INSTANCE.toFlinkCatalogProperties(
                    gravitinoProperties));
    Assertions.assertTrue(exception.getMessage().contains("default-database"));
  }
}
