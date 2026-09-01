/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.integration.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;

/** Applies enterprise JDBC DDL required by SCIM integration tests. */
final class ScimEnterpriseSchemaInitializer {

  private ScimEnterpriseSchemaInitializer() {}

  /**
   * Creates enterprise extension tables on the entity-store JDBC backend.
   *
   * @param config active server configuration
   */
  public static void initialize(Config config) throws IOException {
    JDBCBackendType backendType =
        JDBCBackendType.fromURI(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL));
    String databaseType = toDatabaseDirectory(backendType);

    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        Statement statement = connection.createStatement()) {
      configurePostgreSqlSearchPath(backendType, config, statement);
      executeSqlStatements(statement, loadEnterpriseSchemaStatements(databaseType));
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize enterprise schema for SCIM IT", e);
    }
  }

  private static void configurePostgreSqlSearchPath(
      JDBCBackendType backendType, Config config, Statement statement) throws SQLException {
    if (backendType != JDBCBackendType.POSTGRESQL) {
      return;
    }

    String currentSchema =
        parsePostgreSqlCurrentSchema(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL));
    if (StringUtils.isBlank(currentSchema)) {
      return;
    }

    statement.execute("SET search_path TO " + currentSchema);
  }

  @Nullable
  private static String parsePostgreSqlCurrentSchema(String jdbcUrl) {
    String parameterPrefix = "currentSchema=";
    int index = jdbcUrl.indexOf(parameterPrefix);
    if (index < 0) {
      return null;
    }

    String schema = jdbcUrl.substring(index + parameterPrefix.length());
    int ampersandIndex = schema.indexOf('&');
    if (ampersandIndex >= 0) {
      schema = schema.substring(0, ampersandIndex);
    }
    return schema;
  }

  private static String toDatabaseDirectory(JDBCBackendType backendType) {
    switch (backendType) {
      case H2:
        return "h2";
      case MYSQL:
        return "mysql";
      case POSTGRESQL:
        return "postgresql";
      default:
        throw new IllegalArgumentException("Unsupported JDBC backend: " + backendType);
    }
  }

  private static String[] loadEnterpriseSchemaStatements(String databaseType) throws IOException {
    Path scriptPath =
        resolveProjectRoot()
            .resolve("scripts")
            .resolve("enterprise")
            .resolve(databaseType)
            .resolve(
                String.format(
                    "enterprise-schema-%s-%s.sql",
                    ConfigConstants.CURRENT_SCRIPT_VERSION, databaseType));
    return splitSqlStatements(Files.readString(scriptPath));
  }

  private static String[] splitSqlStatements(String sqlContent) {
    return Arrays.stream(sqlContent.split(";"))
        .map(String::trim)
        .filter(sql -> !sql.isEmpty())
        .toArray(String[]::new);
  }

  private static void executeSqlStatements(Statement statement, String[] sqlStatements)
      throws SQLException {
    for (String sql : sqlStatements) {
      statement.execute(sql);
    }
  }

  private static Path resolveProjectRoot() {
    String rootDir = System.getenv("GRAVITINO_ROOT_DIR");
    if (StringUtils.isBlank(rootDir)) {
      rootDir = System.getenv("GRAVITINO_HOME");
    }

    if (StringUtils.isBlank(rootDir)) {
      throw new IllegalStateException(
          "GRAVITINO_ROOT_DIR or GRAVITINO_HOME must be set for SCIM integration tests");
    }

    return Path.of(rootDir);
  }
}
