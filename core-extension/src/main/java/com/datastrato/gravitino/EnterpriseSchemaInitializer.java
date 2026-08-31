/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Initializes the Enterprise extension schema for an embedded relational backend. */
final class EnterpriseSchemaInitializer {
  private static final Logger LOG = LoggerFactory.getLogger(EnterpriseSchemaInitializer.class);

  private EnterpriseSchemaInitializer() {}

  /** Applies the current Enterprise schema when the entity store uses embedded H2. */
  static void initialize(Config config) {
    if (!Configs.RELATIONAL_ENTITY_STORE.equalsIgnoreCase(config.get(Configs.ENTITY_STORE))
        || !Configs.DEFAULT_ENTITY_RELATIONAL_STORE.equalsIgnoreCase(
            config.get(Configs.ENTITY_RELATIONAL_STORE))) {
      return;
    }

    JDBCBackendType backendType =
        JDBCBackendType.fromURI(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL));
    if (backendType != JDBCBackendType.H2) {
      return;
    }

    Path schemaPath = resolveEnterpriseSchemaPath();
    try {
      List<String> statements = loadStatements(schemaPath);
      try (SqlSession session =
              SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
          Statement statement = session.getConnection().createStatement()) {
        for (String sql : statements) {
          statement.execute(sql);
        }
      }
      LOG.info("Initialized Enterprise H2 schema from {}", schemaPath.getFileName());
    } catch (IOException | SQLException e) {
      throw new RuntimeException("Failed to initialize Enterprise H2 schema", e);
    }
  }

  private static Path resolveEnterpriseSchemaPath() {
    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    if (StringUtils.isBlank(gravitinoHome)) {
      gravitinoHome = System.getenv("GRAVITINO_ROOT_DIR");
    }
    if (StringUtils.isBlank(gravitinoHome)) {
      throw new IllegalStateException("GRAVITINO_HOME or GRAVITINO_ROOT_DIR must be set");
    }

    return Path.of(
        gravitinoHome,
        "scripts",
        "enterprise",
        "h2",
        String.format("enterprise-schema-%s-h2.sql", ConfigConstants.CURRENT_SCRIPT_VERSION));
  }

  private static List<String> loadStatements(Path schemaPath) throws IOException {
    String executableSql =
        Files.readAllLines(schemaPath, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("--"))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

    return Arrays.stream(executableSql.split(";"))
        .map(String::trim)
        .filter(sql -> !sql.isEmpty())
        .toList();
  }
}
