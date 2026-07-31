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
package org.apache.gravitino.integration.test.container;

import static java.lang.String.format;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.rnorth.ducttape.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

public class SqlServerContainer extends BaseContainer {
  public static final Logger LOG = LoggerFactory.getLogger(SqlServerContainer.class);
  public static final String DEFAULT_IMAGE = "mcr.microsoft.com/mssql/server:2019-latest";
  public static final String HOST_NAME = "gravitino-ci-mssql";
  public static final int MSSQL_PORT = 1433;
  public static final String USER_NAME = "sa";
  public static final String PASSWORD = "Gravitino@123";

  private static final int CONTAINER_STARTUP_TIMEOUT_SECONDS = 180;

  // SQL Server error 1801: "Database '%.*ls' already exists."
  private static final int ERROR_DATABASE_ALREADY_EXISTS = 1801;

  public static Builder builder() {
    return new Builder();
  }

  protected SqlServerContainer(
      String image,
      String hostName,
      Set<Integer> ports,
      Map<String, String> extraHosts,
      Map<String, String> filesToMount,
      Map<String, String> envVars,
      Optional<Network> network) {
    super(image, hostName, ports, extraHosts, filesToMount, envVars, network);
  }

  @Override
  protected void setupContainer() {
    super.setupContainer();
    withLogConsumer(new PrintingContainerLog(format("%-18s| ", "SqlServerContainer")));
  }

  @Override
  public void start() {
    super.start();
    Preconditions.check("SQL Server container startup failed!", checkContainerStatus(10));
  }

  @Override
  protected boolean checkContainerStatus(int retryLimit) {
    try {
      await()
          .atMost(CONTAINER_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .pollInterval(CONTAINER_STARTUP_TIMEOUT_SECONDS / retryLimit, TimeUnit.SECONDS)
          .until(
              () -> {
                try (Connection conn =
                    DriverManager.getConnection(getJdbcUrl(), USER_NAME, PASSWORD)) {
                  LOG.info("SQL Server container startup success!");
                  return true;
                } catch (SQLException e) {
                  LOG.warn("SQL Server container is not ready: {}", e.getMessage());
                  return false;
                } catch (RuntimeException e) {
                  LOG.error("SQL Server container readiness check failed unexpectedly", e);
                  return false;
                }
              });
      return true;
      // The lambda above already catches and swallows SQLException/RuntimeException, so the
      // only exception that can reach here is Awaitility's own (shaded) timeout exception on
      // condition failure; catch RuntimeException generically to avoid importing the shaded
      // type, which Spotless's "Remove Testcontainers shading" rule would rewrite to the
      // unshaded class that await() never actually throws.
    } catch (RuntimeException e) {
      LOG.error("SQL Server container failed to become ready within timeout", e);
      return false;
    }
  }

  /** Omits databaseName so this URL is usable before any test database is created. */
  public String getJdbcUrl() {
    return format(
        "jdbc:sqlserver://%s:%d;encrypt=false;trustServerCertificate=true",
        getContainerIpAddress(), MSSQL_PORT);
  }

  public String getJdbcUrl(TestDatabaseName testDatabaseName) {
    return format(
        "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true",
        getContainerIpAddress(), MSSQL_PORT, testDatabaseName);
  }

  public String getUsername() {
    return USER_NAME;
  }

  public String getPassword() {
    return PASSWORD;
  }

  public String getDriverClassName(TestDatabaseName testDatabaseName) {
    return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
  }

  public void createDatabase(TestDatabaseName testDatabaseName) {
    try (Connection connection = DriverManager.getConnection(getJdbcUrl(), USER_NAME, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(format("CREATE DATABASE [%s]", testDatabaseName));
      LOG.info("SQL Server container database {} has been created", testDatabaseName);
    } catch (SQLException e) {
      if (e.getErrorCode() == ERROR_DATABASE_ALREADY_EXISTS) {
        LOG.info("SQL Server database {} already exists, skipping", testDatabaseName);
      } else {
        throw new RuntimeException(
            format(
                "Failed to create SQL Server database '%s' (errorCode=%d): %s",
                testDatabaseName, e.getErrorCode(), e.getMessage()),
            e);
      }
    }
  }

  public static class Builder
      extends BaseContainer.Builder<SqlServerContainer.Builder, SqlServerContainer> {

    private Builder() {
      this.image = DEFAULT_IMAGE;
      this.hostName = HOST_NAME;
      this.exposePorts = ImmutableSet.of(MSSQL_PORT);
    }

    @Override
    public SqlServerContainer build() {
      return new SqlServerContainer(
          image, hostName, exposePorts, extraHosts, filesToMount, envVars, network);
    }
  }
}
