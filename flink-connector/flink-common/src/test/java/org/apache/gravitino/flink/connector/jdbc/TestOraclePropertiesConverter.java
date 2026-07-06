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

package org.apache.gravitino.flink.connector.jdbc;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.apache.flink.table.catalog.CommonCatalogOptions;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.gravitino.flink.connector.jdbc.oracle.OraclePropertiesConverter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests for {@link OraclePropertiesConverter}. */
public class TestOraclePropertiesConverter extends AbstractJdbcPropertiesConverterTestSuite {

  private static final String ORACLE_URL = "jdbc:oracle:thin:@//192.168.1.1:1521/ORCL";
  private static final String FLINK_BYPASS_DEFAULT_DATABASE = "flink.bypass.default-database";

  @Override
  protected JdbcPropertiesConverter getConverter(Map<String, String> catalogOptions) {
    return OraclePropertiesConverter.INSTANCE;
  }

  /**
   * Oracle URL is stored as-is — no base URL stripping. Override the base-class test which assumes
   * MySQL-style URL transformation.
   */
  @Override
  @Test
  public void testToJdbcCatalogProperties() {
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.GRAVITINO_JDBC_USER,
            username,
            JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD,
            password,
            JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
            ORACLE_URL,
            FLINK_BYPASS_DEFAULT_DATABASE,
            defaultDatabase);
    Map<String, String> result = OraclePropertiesConverter.INSTANCE.toFlinkCatalogProperties(props);
    Assertions.assertEquals(username, result.get(JdbcPropertiesConstants.FLINK_JDBC_USER));
    Assertions.assertEquals(password, result.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD));
    // Oracle URL is stored unchanged — no base URL stripping.
    Assertions.assertEquals(ORACLE_URL, result.get(JdbcPropertiesConstants.FLINK_JDBC_URL));
    Assertions.assertEquals(
        "gravitino-jdbc-oracle", result.get(CommonCatalogOptions.CATALOG_TYPE.key()));
  }

  /**
   * Oracle thin URLs do not have a database path suffix to strip. A URL with query-style parameters
   * must be preserved verbatim (unlike MySQL where the trailing "?param=value" is stripped). This
   * override verifies that the converter passes the URL through unchanged even when extra
   * parameters are present.
   */
  @Override
  @Test
  public void testToJdbcCatalogPropertiesUsingUrlWithParameter() {
    String urlWithParam = "jdbc:oracle:thin:@//192.168.1.1:1521/ORCL?oracle.net.ssl_version=3";
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.GRAVITINO_JDBC_USER,
            username,
            JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD,
            password,
            JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
            urlWithParam,
            FLINK_BYPASS_DEFAULT_DATABASE,
            defaultDatabase);
    Map<String, String> result = OraclePropertiesConverter.INSTANCE.toFlinkCatalogProperties(props);
    // Oracle URL must be stored unchanged — no query-parameter stripping.
    Assertions.assertEquals(urlWithParam, result.get(JdbcPropertiesConstants.FLINK_JDBC_URL));
  }

  /** Oracle URLs do not use //<host>/<db> style. Override the domain URL test. */
  @Override
  @Test
  public void testToJdbcCatalogPropertiesUsingDomainUrl() {
    String domainUrl = "jdbc:oracle:thin:@//db.example.com:1521/ORCL";
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.GRAVITINO_JDBC_USER,
            username,
            JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD,
            password,
            JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
            domainUrl,
            FLINK_BYPASS_DEFAULT_DATABASE,
            defaultDatabase);
    Map<String, String> result = OraclePropertiesConverter.INSTANCE.toFlinkCatalogProperties(props);
    Assertions.assertEquals(domainUrl, result.get(JdbcPropertiesConstants.FLINK_JDBC_URL));
  }

  @Test
  public void testToFlinkTableProperties() {
    Map<String, String> flinkCatalogProps =
        ImmutableMap.of(
            JdbcPropertiesConstants.FLINK_JDBC_USER,
            username,
            JdbcPropertiesConstants.FLINK_JDBC_PASSWORD,
            password,
            JdbcPropertiesConstants.FLINK_JDBC_URL,
            ORACLE_URL);
    ObjectPath tablePath = new ObjectPath("gravitino", "my_table");
    Map<String, String> tableProps =
        OraclePropertiesConverter.INSTANCE.toFlinkTableProperties(
            flinkCatalogProps, new HashMap<>(), tablePath);

    // URL must be the Oracle connection URL unchanged (no schema appended).
    Assertions.assertEquals(
        ORACLE_URL, tableProps.get(JdbcPropertiesConstants.FLINK_JDBC_TABLE_DATABASE_URL));
    // Both schema and table are uppercased so unquoted Oracle SQL finds the identifier.
    Assertions.assertEquals(
        "GRAVITINO.MY_TABLE", tableProps.get(JdbcPropertiesConstants.FLINK_JDBC_TABLE_NAME));
    Assertions.assertEquals(username, tableProps.get(JdbcPropertiesConstants.FLINK_JDBC_USER));
    Assertions.assertEquals(password, tableProps.get(JdbcPropertiesConstants.FLINK_JDBC_PASSWORD));
  }

  @Test
  public void testDefaultDriverName() {
    Assertions.assertEquals(
        "oracle.jdbc.OracleDriver", OraclePropertiesConverter.INSTANCE.defaultDriverName());
  }

  @Test
  public void testToFlinkTablePropertiesMissingUser() {
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.FLINK_JDBC_PASSWORD, password,
            JdbcPropertiesConstants.FLINK_JDBC_URL, ORACLE_URL);
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            OraclePropertiesConverter.INSTANCE.toFlinkTableProperties(
                props, new HashMap<>(), new ObjectPath("SC", "T")));
  }

  @Test
  public void testToFlinkTablePropertiesMissingPassword() {
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.FLINK_JDBC_USER, username,
            JdbcPropertiesConstants.FLINK_JDBC_URL, ORACLE_URL);
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            OraclePropertiesConverter.INSTANCE.toFlinkTableProperties(
                props, new HashMap<>(), new ObjectPath("SC", "T")));
  }

  @Test
  public void testToFlinkTablePropertiesMissingUrl() {
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.FLINK_JDBC_USER, username,
            JdbcPropertiesConstants.FLINK_JDBC_PASSWORD, password);
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            OraclePropertiesConverter.INSTANCE.toFlinkTableProperties(
                props, new HashMap<>(), new ObjectPath("SC", "T")));
  }

  @Test
  public void testToFlinkCatalogPropertiesFlinkBypassPrefixStripping() {
    Map<String, String> props =
        ImmutableMap.of(
            JdbcPropertiesConstants.GRAVITINO_JDBC_USER,
            username,
            JdbcPropertiesConstants.GRAVITINO_JDBC_PASSWORD,
            password,
            JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
            ORACLE_URL,
            "flink.bypass.some-key",
            "some-value");
    Map<String, String> result = OraclePropertiesConverter.INSTANCE.toFlinkCatalogProperties(props);
    // flink.bypass.* prefix must be stripped; the bare key should appear in Flink options.
    Assertions.assertEquals("some-value", result.get("some-key"));
    Assertions.assertNull(result.get("flink.bypass.some-key"));
  }
}
