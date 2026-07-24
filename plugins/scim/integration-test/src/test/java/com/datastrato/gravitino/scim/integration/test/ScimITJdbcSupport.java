/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.integration.test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Configs;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.container.PostgreSQLContainer;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JDBC backend bootstrap for SCIM service integration tests (without {@code BaseIT}). */
final class ScimITJdbcSupport {

  private static final Logger LOG = LoggerFactory.getLogger(ScimITJdbcSupport.class);

  private static final ContainerSuite CONTAINER_SUITE = ContainerSuite.getInstance();

  private ScimITJdbcSupport() {}

  /**
   * Applies relational entity-store settings for the active {@code jdbcBackend} environment
   * variable.
   *
   * @param customConfigs server configuration overrides for {@link
   *     org.apache.gravitino.integration.test.MiniGravitino}
   */
  static void configureJdbcBackend(Map<String, String> customConfigs) {
    String backend = getJdbcBackend();
    if ("MySQL".equalsIgnoreCase(backend)) {
      configureMySql(customConfigs);
    } else if ("PostgreSQL".equalsIgnoreCase(backend)) {
      configurePostgreSql(customConfigs);
    }
  }

  private static void configureMySql(Map<String, String> customConfigs) {
    TestDatabaseName databaseName = TestDatabaseName.MYSQL_JDBC_BACKEND;
    CONTAINER_SUITE.startMySQLContainer(databaseName);
    MySQLContainer container = CONTAINER_SUITE.getMySQLContainer();
    String mysqlUrl = container.getJdbcUrl(databaseName);
    LOG.info("MySQL URL: {}", mysqlUrl);

    try (Connection connection =
            DriverManager.getConnection(
                StringUtils.substring(mysqlUrl, 0, mysqlUrl.lastIndexOf("/")), "root", "root");
        Statement statement = connection.createStatement()) {
      statement.execute("drop database if exists " + databaseName);
      statement.execute("create database " + databaseName);
      String gravitinoHome = System.getenv("GRAVITINO_ROOT_DIR");
      String mysqlContent =
          FileUtils.readFileToString(
              new File(
                  gravitinoHome
                      + String.format(
                          "/scripts/mysql/schema-%s-mysql.sql",
                          ConfigConstants.CURRENT_SCRIPT_VERSION)),
              "UTF-8");
      String[] initMySqlBackendSqls =
          Arrays.stream(mysqlContent.split(";"))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .toArray(String[]::new);
      initMySqlBackendSqls = ArrayUtils.addFirst(initMySqlBackendSqls, "use " + databaseName + ";");
      for (String sql : initMySqlBackendSqls) {
        statement.execute(sql);
      }
    } catch (Exception e) {
      LOG.error("Failed to initialize MySQL backend for SCIM IT", e);
      throw new RuntimeException("Failed to initialize MySQL backend for SCIM IT", e);
    }

    customConfigs.put(Configs.ENTITY_STORE_KEY, "relational");
    customConfigs.put(Configs.ENTITY_RELATIONAL_STORE_KEY, "JDBCBackend");
    customConfigs.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL_KEY, mysqlUrl);
    customConfigs.put(
        Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER_KEY, "com.mysql.cj.jdbc.Driver");
    customConfigs.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER_KEY, "root");
    customConfigs.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD_KEY, "root");
  }

  private static void configurePostgreSql(Map<String, String> customConfigs) {
    try {
      doConfigurePostgreSql(customConfigs);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize PostgreSQL backend for SCIM IT", e);
    }
  }

  private static void doConfigurePostgreSql(Map<String, String> customConfigs) throws Exception {
    TestDatabaseName databaseName = TestDatabaseName.PG_JDBC_BACKEND;
    CONTAINER_SUITE.startPostgreSQLContainer(databaseName);
    PostgreSQLContainer container = CONTAINER_SUITE.getPostgreSQLContainer();

    String pgUrlWithoutSchema = container.getJdbcUrl(databaseName);
    String randomSchemaName = RandomStringUtils.random(10, true, false);
    String currentExecuteSql = "";
    try (Connection connection =
            DriverManager.getConnection(
                pgUrlWithoutSchema, container.getUsername(), container.getPassword());
        Statement statement = connection.createStatement()) {
      connection.setCatalog(TestDatabaseName.PG_CATALOG_POSTGRESQL_IT.toString());
      statement.execute("drop schema if exists " + randomSchemaName);
      statement.execute("create schema " + randomSchemaName);
      statement.execute("set search_path to " + randomSchemaName);
      String gravitinoHome = System.getenv("GRAVITINO_ROOT_DIR");
      String pgContent =
          FileUtils.readFileToString(
              new File(
                  gravitinoHome
                      + String.format(
                          "/scripts/postgresql/schema-%s-postgresql.sql",
                          ConfigConstants.CURRENT_SCRIPT_VERSION)),
              "UTF-8");
      for (String sql :
          Arrays.stream(pgContent.split(";"))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .toArray(String[]::new)) {
        currentExecuteSql = sql;
        statement.execute(sql);
      }
    } catch (Exception e) {
      LOG.error("Failed to initialize PostgreSQL backend for SCIM IT, sql:\n{}", currentExecuteSql);
      throw e;
    }

    pgUrlWithoutSchema = pgUrlWithoutSchema + "?currentSchema=" + randomSchemaName;
    customConfigs.put(Configs.ENTITY_STORE_KEY, "relational");
    customConfigs.put(Configs.ENTITY_RELATIONAL_STORE_KEY, "JDBCBackend");
    customConfigs.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL_KEY, pgUrlWithoutSchema);
    customConfigs.put(
        Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER_KEY,
        container.getDriverClassName(databaseName));
    customConfigs.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER_KEY, container.getUsername());
    customConfigs.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD_KEY, container.getPassword());
    LOG.info("PostgreSQL URL: {}", pgUrlWithoutSchema);
  }

  static String getJdbcBackend() {
    return System.getenv("jdbcBackend");
  }
}
