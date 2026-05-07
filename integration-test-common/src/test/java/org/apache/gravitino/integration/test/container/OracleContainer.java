/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.container;

import static java.lang.String.format;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.rnorth.ducttape.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

public class OracleContainer extends BaseContainer {
  public static final Logger LOG = LoggerFactory.getLogger(OracleContainer.class);

  // Oracle Database Free 23c — the only gvenzl image that starts reliably on Apple Silicon under
  // AMD64 emulation (XE 11g fails sxecheck4/ORA-45301 single-instance, XE 18c exceeds our 10-min
  // init timeout). Exposes a pluggable database named FREEPDB1.
  public static final String DEFAULT_IMAGE = "gvenzl/oracle-free:slim-faststart";
  public static final String HOST_NAME = "gravitino-ci-oracle";
  public static final int ORACLE_PORT = 1521;

  // Password for the SYS/SYSTEM users; APP_USER below reuses the same password for simplicity.
  public static final String PASSWORD = "gravitino";
  // Application user created automatically by gvenzl/oracle images when APP_USER is set. In Oracle,
  // a user is also the owner of a schema — catalog tests operate under this schema.
  public static final String APP_USER = "GRAVITINO";
  // Oracle Free 23c exposes a pluggable database named FREEPDB1.
  public static final String SERVICE_NAME = "FREEPDB1";

  public static Builder builder() {
    return new Builder();
  }

  protected OracleContainer(
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
    withStartupTimeout(Duration.ofMinutes(10));
    withLogConsumer(new PrintingContainerLog(format("%-14s| ", "OracleContainer")));
  }

  @Override
  public void start() {
    super.start();
    Preconditions.check("Oracle container startup failed!", checkContainerStatus(30));
  }

  @Override
  protected boolean checkContainerStatus(int retryLimit) {
    // Oracle takes notably longer than MySQL/PG to become ready, so poll with generous timeouts.
    await()
        .atMost(10, TimeUnit.MINUTES)
        .pollInterval(10, TimeUnit.SECONDS)
        .until(
            () -> {
              try (Connection ignored =
                  DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword())) {
                LOG.info("Oracle container startup success!");
                return true;
              } catch (SQLException e) {
                LOG.info("Oracle container is not ready yet: {}", e.getMessage());
                return false;
              }
            });
    return true;
  }

  /**
   * Oracle has no CREATE DATABASE semantics comparable to MySQL/PG. The APP_USER (a.k.a. schema) is
   * created at container boot time by the image entrypoint. This method is a no-op but kept so the
   * container has the same shape as its siblings.
   */
  public void createDatabase(TestDatabaseName testDatabaseName) {
    LOG.info(
        "Oracle does not provision a separate database per test — APP_USER {} is shared.",
        APP_USER);
  }

  public String getUsername() {
    return APP_USER;
  }

  public String getPassword() {
    return PASSWORD;
  }

  public String getJdbcUrl() {
    return format(
        "jdbc:oracle:thin:@//%s:%d/%s", getContainerIpAddress(), ORACLE_PORT, SERVICE_NAME);
  }

  public String getJdbcUrl(TestDatabaseName testDatabaseName) {
    return getJdbcUrl();
  }

  public String getDriverClassName(TestDatabaseName testDatabaseName) throws SQLException {
    return DriverManager.getDriver(getJdbcUrl(testDatabaseName)).getClass().getName();
  }

  public static class Builder
      extends BaseContainer.Builder<OracleContainer.Builder, OracleContainer> {

    private Builder() {
      this.image = DEFAULT_IMAGE;
      this.hostName = HOST_NAME;
      this.exposePorts = ImmutableSet.of(ORACLE_PORT);
    }

    @Override
    public OracleContainer build() {
      return new OracleContainer(
          image, hostName, exposePorts, extraHosts, filesToMount, envVars, network);
    }
  }
}
