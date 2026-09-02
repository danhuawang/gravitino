/*
 * Copyright 2026 Datastrato Inc.
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

/**
 * JDBC bootstrap for SCIM relational storage.
 *
 * <p>When the Gravitino entity store already owns the embedded H2 database and MyBatis session
 * factory (the MiniGravitino / production server path), this class only ensures the SCIM SQL
 * exception converter is initialized and does <em>not</em> start or shut down H2. Starting a second
 * {@link H2Database} (or calling {@code SHUTDOWN} from a nested manager {@code close()}) races the
 * shared entity-store connection pool.
 */
public final class ScimRelationalStorage implements Closeable {

  private static final Map<JDBCBackendType, String> EMBEDDED_JDBC_DATABASE_MAP =
      ImmutableMap.of(JDBCBackendType.H2, H2Database.class.getCanonicalName());

  private final boolean ownsEmbeddedDatabase;
  private JDBCDatabase jdbcDatabase;

  /**
   * Initializes the JDBC session factory and optional embedded database.
   *
   * @param config the server configuration
   */
  public ScimRelationalStorage(Config config) {
    boolean sessionFactoryAlreadyInitialized = isSqlSessionFactoryInitialized();
    if (sessionFactoryAlreadyInitialized) {
      ownsEmbeddedDatabase = false;
      jdbcDatabase = null;
    } else {
      jdbcDatabase = startEmbeddedDatabaseIfNecessary(config);
      ownsEmbeddedDatabase = jdbcDatabase != null;
    }
    SqlSessionFactoryHelper.getInstance().init(config);
    ScimSQLExceptionConverterFactory.initConverter(config);
  }

  @Override
  public void close() throws IOException {
    // Never tear down the shared entity-store session factory / H2 instance from a nested SCIM
    // manager close(); TokenManager, UserManager, and GroupManager each hold a storage handle.
    if (!ownsEmbeddedDatabase) {
      return;
    }
    SqlSessionFactoryHelper.getInstance().close();
    ScimSQLExceptionConverterFactory.close();
    if (jdbcDatabase != null) {
      jdbcDatabase.close();
      jdbcDatabase = null;
    }
  }

  private static boolean isSqlSessionFactoryInitialized() {
    try {
      SqlSessionFactoryHelper.getInstance().getSqlSessionFactory();
      return true;
    } catch (IllegalStateException notInitialized) {
      return false;
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
