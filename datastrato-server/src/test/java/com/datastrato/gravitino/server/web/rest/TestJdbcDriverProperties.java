/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestJdbcDriverProperties {

  private static final String JDBC_DRIVER = "jdbc-driver";

  @Test
  public void testPureJdbcProvidersGetTheirDriver() {
    Map<String, String> expected =
        ImmutableMap.<String, String>builder()
            .put("jdbc-bigquery", "com.simba.googlebigquery.jdbc42.Driver")
            .put("jdbc-clickhouse", "com.clickhouse.jdbc.ClickHouseDriver")
            .put("jdbc-doris", "com.mysql.cj.jdbc.Driver")
            .put("jdbc-hologres", "org.postgresql.Driver")
            .put("jdbc-maxcompute", "com.aliyun.odps.jdbc.OdpsDriver")
            .put("jdbc-mysql", "com.mysql.cj.jdbc.Driver")
            .put("jdbc-oceanbase", "com.mysql.cj.jdbc.Driver")
            .put("jdbc-oracle", "oracle.jdbc.OracleDriver")
            .put("jdbc-postgresql", "org.postgresql.Driver")
            .put("jdbc-sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver")
            .put("jdbc-starrocks", "com.mysql.cj.jdbc.Driver")
            .build();

    expected.forEach(
        (provider, driver) -> {
          Map<String, String> result =
              JdbcDriverProperties.withDefaultDriver(provider, ImmutableMap.of("key", "value"));
          Assertions.assertEquals(driver, result.get(JDBC_DRIVER), provider);
          Assertions.assertEquals("value", result.get("key"));
        });
  }

  @Test
  public void testProviderMatchIsCaseInsensitive() {
    Map<String, String> result =
        JdbcDriverProperties.withDefaultDriver("JDBC-MySQL", ImmutableMap.of());
    Assertions.assertEquals("com.mysql.cj.jdbc.Driver", result.get(JDBC_DRIVER));
  }

  @Test
  public void testExplicitDriverIsPreserved() {
    Map<String, String> input = ImmutableMap.of(JDBC_DRIVER, "custom.Driver");
    Map<String, String> result = JdbcDriverProperties.withDefaultDriver("jdbc-mysql", input);
    Assertions.assertEquals("custom.Driver", result.get(JDBC_DRIVER));
    // Nothing to add, so the original map is returned unchanged.
    Assertions.assertSame(input, result);
  }

  @Test
  public void testNonJdbcProviderUntouched() {
    Map<String, String> input = ImmutableMap.of("uri", "thrift://hms:9083");
    Map<String, String> result = JdbcDriverProperties.withDefaultDriver("hive", input);
    Assertions.assertFalse(result.containsKey(JDBC_DRIVER));
    Assertions.assertSame(input, result);
  }

  @Test
  public void testNullProviderReturnsPropertiesUnchanged() {
    Map<String, String> input = ImmutableMap.of("key", "value");
    Assertions.assertSame(input, JdbcDriverProperties.withDefaultDriver(null, input));
    Assertions.assertNull(JdbcDriverProperties.withDefaultDriver(null, null));
  }

  @Test
  public void testJdbcProviderWithNullPropertiesGetsDriverMap() {
    Map<String, String> result = JdbcDriverProperties.withDefaultDriver("jdbc-mysql", null);
    Assertions.assertEquals("com.mysql.cj.jdbc.Driver", result.get(JDBC_DRIVER));
  }

  @Test
  public void testInputMapIsNeverMutated() {
    Map<String, String> input = new HashMap<>();
    input.put("key", "value");
    JdbcDriverProperties.withDefaultDriver("jdbc-mysql", input);
    Assertions.assertFalse(input.containsKey(JDBC_DRIVER));
  }

  @Test
  public void testIcebergJdbcBackendDerivesDriverFromUri() {
    Map<String, String> pg =
        ImmutableMap.of("catalog-backend", "jdbc", "uri", "jdbc:postgresql://pg:5432/demo");
    Assertions.assertEquals(
        "org.postgresql.Driver",
        JdbcDriverProperties.withDefaultDriver("lakehouse-iceberg", pg).get(JDBC_DRIVER));

    Map<String, String> mysql =
        ImmutableMap.of("catalog-backend", "jdbc", "uri", "jdbc:mysql://mysql:3306/demo");
    Assertions.assertEquals(
        "com.mysql.cj.jdbc.Driver",
        JdbcDriverProperties.withDefaultDriver("lakehouse-iceberg", mysql).get(JDBC_DRIVER));
  }

  @Test
  public void testIcebergNonJdbcBackendUntouched() {
    Map<String, String> hive = ImmutableMap.of("catalog-backend", "hive", "uri", "thrift://hms");
    Assertions.assertFalse(
        JdbcDriverProperties.withDefaultDriver("lakehouse-iceberg", hive).containsKey(JDBC_DRIVER));

    Map<String, String> rest =
        ImmutableMap.of("catalog-backend", "rest", "uri", "http://rest:9001/iceberg");
    Assertions.assertFalse(
        JdbcDriverProperties.withDefaultDriver("lakehouse-iceberg", rest).containsKey(JDBC_DRIVER));
  }

  @Test
  public void testIcebergOnlyDerivesMysqlAndPostgresql() {
    // Iceberg JDBC backend auto-derives only MySQL and PostgreSQL (documented databases whose
    // drivers ship on the server classpath). Other schemes are left untouched so the user can
    // still connect any database by supplying jdbc-driver explicitly.
    for (String uri :
        new String[] {
          "jdbc:sqlite::memory:",
          "jdbc:oracle:thin:@host:1521:orcl",
          "jdbc:sqlserver://host:1433;databaseName=db",
          "jdbc:unknowndb://host/demo"
        }) {
      Map<String, String> props = ImmutableMap.of("catalog-backend", "jdbc", "uri", uri);
      Assertions.assertFalse(
          JdbcDriverProperties.withDefaultDriver("lakehouse-iceberg", props)
              .containsKey(JDBC_DRIVER),
          uri);
    }
  }

  @Test
  public void testIcebergExplicitDriverPreserved() {
    Map<String, String> props =
        ImmutableMap.of(
            "catalog-backend",
            "jdbc",
            "uri",
            "jdbc:mysql://mysql:3306/demo",
            JDBC_DRIVER,
            "custom.Driver");
    Assertions.assertEquals(
        "custom.Driver",
        JdbcDriverProperties.withDefaultDriver("lakehouse-iceberg", props).get(JDBC_DRIVER));
  }
}
