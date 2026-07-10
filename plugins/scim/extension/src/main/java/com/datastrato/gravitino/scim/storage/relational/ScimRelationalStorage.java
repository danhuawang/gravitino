/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational;

import com.datastrato.gravitino.scim.storage.relational.converters.ScimSQLExceptionConverterFactory;
import com.google.common.collect.ImmutableMap;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.JDBCDatabase;
import org.apache.gravitino.storage.relational.database.H2Database;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;

/** JDBC bootstrap for SCIM token relational storage. */
public final class ScimRelationalStorage implements Closeable {

  private static final Map<JDBCBackendType, String> EMBEDDED_JDBC_DATABASE_MAP =
      ImmutableMap.of(JDBCBackendType.H2, H2Database.class.getCanonicalName());

  private JDBCDatabase jdbcDatabase;

  /**
   * Initializes the JDBC session factory and optional embedded database.
   *
   * @param config the server configuration
   */
  public ScimRelationalStorage(Config config) {
    jdbcDatabase = startEmbeddedDatabaseIfNecessary(config);
    SqlSessionFactoryHelper.getInstance().init(config);
    ScimSQLExceptionConverterFactory.initConverter(config);
  }

  @Override
  public void close() throws IOException {
    SqlSessionFactoryHelper.getInstance().close();
    ScimSQLExceptionConverterFactory.close();
    if (jdbcDatabase != null) {
      jdbcDatabase.close();
      jdbcDatabase = null;
    }
  }

  private JDBCDatabase startEmbeddedDatabaseIfNecessary(Config config) {
    String jdbcUrl = config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL);
    JDBCBackendType jdbcBackendType = JDBCBackendType.fromURI(jdbcUrl);
    if (jdbcBackendType != JDBCBackendType.H2) {
      return null;
    }

    try {
      JDBCDatabase database =
          (JDBCDatabase)
              Class.forName(EMBEDDED_JDBC_DATABASE_MAP.get(jdbcBackendType))
                  .getDeclaredConstructor()
                  .newInstance();
      database.initialize(config);
      return database;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create and initialize SCIM JDBC backend.", e);
    }
  }
}
