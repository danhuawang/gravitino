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
package org.apache.gravitino.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.container.PostgreSQLContainer;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Validates enterprise extension JDBC DDL scripts under {@code scripts/enterprise/{backend}/}.
 *
 * <p>Uses an isolated empty database per backend invocation and executes scripts through native
 * JDBC, without sharing {@code TestJDBCBackend} state or MyBatis session factories.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class TestEnterpriseSQLScripts {

  private static final String DOCKER_TEST_FLAG = "dockerTest";
  private static final String H2_BACKEND = "h2";
  private static final String MYSQL_BACKEND = "mysql";
  private static final String POSTGRESQL_BACKEND = "postgresql";

  private Path h2DatabasePath;
  private String mysqlDatabaseName;
  private String postgresSchemaName;

  static Stream<String> backendProvider() {
    Stream.Builder<String> backends = Stream.builder();
    backends.add(H2_BACKEND);
    if ("true".equalsIgnoreCase(System.getenv(DOCKER_TEST_FLAG))) {
      backends.add(MYSQL_BACKEND);
      backends.add(POSTGRESQL_BACKEND);
    }
    return backends.build();
  }

  @AfterEach
  void tearDown() throws IOException {
    ContainerSuite.getInstance().close();
    if (h2DatabasePath != null && Files.exists(h2DatabasePath)) {
      deleteDirectory(h2DatabasePath);
      h2DatabasePath = null;
    }
    mysqlDatabaseName = null;
    postgresSchemaName = null;
  }

  @ParameterizedTest(name = "[{0} backend]")
  @MethodSource("backendProvider")
  public void testEnterpriseSQLScripts(String backendType) throws SQLException, IOException {
    String backend = backendType.toLowerCase();
    Path scriptDir = resolveProjectRoot().resolve("scripts").resolve("enterprise").resolve(backend);

    File[] scriptFiles = scriptDir.toFile().listFiles();
    Assertions.assertNotNull(scriptFiles, "No enterprise script files found in " + scriptDir);

    Pattern schemaPattern = Pattern.compile("enterprise-schema-([\\d.]+)-" + backend + "\\.sql");

    Map<String, List<File>> versionScripts = new HashMap<>();
    for (File scriptFile : scriptFiles) {
      if (!scriptFile.isFile() || !scriptFile.getName().endsWith(".sql")) {
        continue;
      }

      Matcher schemaMatcher = schemaPattern.matcher(scriptFile.getName());
      if (schemaMatcher.matches()) {
        String version = schemaMatcher.group(1);
        versionScripts.computeIfAbsent(version, k -> new ArrayList<>()).add(scriptFile);
      } else {
        Assertions.fail("Unrecognized enterprise script file name: " + scriptFile.getName());
      }
    }

    Assertions.assertFalse(versionScripts.isEmpty(), "No enterprise schema scripts found");

    try (EmptyDatabase database = createEmptyDatabase(backendType)) {
      for (List<File> scripts : versionScripts.values()) {
        scripts.sort(Comparator.comparing(File::getName));
        dropAllTables(database.openConnection(), backendType);
        for (File scriptFile : scripts) {
          List<String> ddls = extractStatements(scriptFile.toPath());
          try (Connection connection = database.openConnection();
              Statement statement = connection.createStatement()) {
            for (String ddl : ddls) {
              Assertions.assertDoesNotThrow(
                  () -> statement.execute(ddl),
                  "Failed to execute DDL in file " + scriptFile.getName() + ", ddl: " + ddl);
            }
          }
        }
      }
    }
  }

  private EmptyDatabase createEmptyDatabase(String backendType) throws IOException, SQLException {
    switch (backendType) {
      case H2_BACKEND:
        return createEmptyH2Database();
      case MYSQL_BACKEND:
        return createEmptyMySQLDatabase();
      case POSTGRESQL_BACKEND:
        return createEmptyPostgreSQLDatabase();
      default:
        throw new IllegalArgumentException("Unsupported backend type: " + backendType);
    }
  }

  private EmptyDatabase createEmptyH2Database() throws IOException {
    h2DatabasePath = Files.createTempDirectory("gravitino_enterprise_ddl_h2_");
    String jdbcUrl =
        String.format(
            "jdbc:h2:file:%s;DB_CLOSE_DELAY=-1;MODE=MySQL", h2DatabasePath.toAbsolutePath());
    return new EmptyDatabase(jdbcUrl, "root", "123456");
  }

  private EmptyDatabase createEmptyMySQLDatabase() throws IOException, SQLException {
    ContainerSuite containerSuite = ContainerSuite.getInstance();
    containerSuite.startMySQLContainer(TestDatabaseName.MYSQL_JDBC_BACKEND);
    MySQLContainer mySQLContainer = containerSuite.getMySQLContainer();
    mysqlDatabaseName = "enterprise_ddl_" + UUID.randomUUID().toString().replace("-", "");
    String serverJdbcUrl =
        StringUtils.substringBeforeLast(
            mySQLContainer.getJdbcUrl(TestDatabaseName.MYSQL_JDBC_BACKEND), "/");

    try (Connection connection =
            DriverManager.getConnection(
                serverJdbcUrl, mySQLContainer.getUsername(), mySQLContainer.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + mysqlDatabaseName);
      statement.execute("CREATE DATABASE " + mysqlDatabaseName);
    }

    String jdbcUrl = serverJdbcUrl + "/" + mysqlDatabaseName;
    return new EmptyDatabase(jdbcUrl, mySQLContainer.getUsername(), mySQLContainer.getPassword());
  }

  private EmptyDatabase createEmptyPostgreSQLDatabase() throws IOException, SQLException {
    ContainerSuite containerSuite = ContainerSuite.getInstance();
    containerSuite.startPostgreSQLContainer(TestDatabaseName.PG_JDBC_BACKEND);
    PostgreSQLContainer postgreSQLContainer = containerSuite.getPostgreSQLContainer();
    postgresSchemaName = "enterprise_ddl_" + UUID.randomUUID().toString().replace("-", "");
    String jdbcUrl = postgreSQLContainer.getJdbcUrl(TestDatabaseName.PG_JDBC_BACKEND);

    try (Connection connection =
            DriverManager.getConnection(
                jdbcUrl, postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + postgresSchemaName + " CASCADE");
      statement.execute("CREATE SCHEMA " + postgresSchemaName);
      statement.execute("SET search_path TO " + postgresSchemaName);
    }

    return new EmptyDatabase(
        jdbcUrl + "?currentSchema=" + postgresSchemaName,
        postgreSQLContainer.getUsername(),
        postgreSQLContainer.getPassword());
  }

  private List<String> extractStatements(Path sqlFile) throws IOException {
    String executableSql =
        Files.readAllLines(sqlFile).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("--"))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

    return Arrays.stream(executableSql.split(";"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private void dropAllTables(Connection connection, String backendType) throws SQLException {
    if (POSTGRESQL_BACKEND.equalsIgnoreCase(backendType)) {
      dropAllTablesForPostgreSQL(connection);
    } else {
      dropAllTablesForMySQLCompatible(connection);
    }
  }

  private void dropAllTablesForMySQLCompatible(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      List<String> tableList = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery("SHOW TABLES")) {
        while (rs.next()) {
          tableList.add(rs.getString(1));
        }
      }
      for (String table : tableList) {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  private void dropAllTablesForPostgreSQL(Connection connection) throws SQLException {
    List<String> tableList = new ArrayList<>();
    String query =
        "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()";
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(query)) {
      while (rs.next()) {
        tableList.add(rs.getString(1));
      }
    }

    if (tableList.isEmpty()) {
      return;
    }

    try (Statement statement = connection.createStatement()) {
      for (String table : tableList) {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  private Path resolveProjectRoot() {
    String rootDir = System.getenv("GRAVITINO_ROOT_DIR");
    if (StringUtils.isBlank(rootDir)) {
      rootDir = System.getenv("GRAVITINO_HOME");
    }
    Assertions.assertFalse(
        StringUtils.isBlank(rootDir),
        "GRAVITINO_ROOT_DIR or GRAVITINO_HOME must be set for enterprise SQL script tests");
    return Path.of(rootDir);
  }

  private void deleteDirectory(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
    }
  }

  private void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new RuntimeException("Delete path failed: " + path, e);
    }
  }

  private static final class EmptyDatabase implements AutoCloseable {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    private EmptyDatabase(String jdbcUrl, String username, String password) {
      this.jdbcUrl = jdbcUrl;
      this.username = username;
      this.password = password;
    }

    private Connection openConnection() throws SQLException {
      return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public void close() {
      // Per-invocation cleanup is handled by {@link #tearDown()}.
    }
  }
}
