/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import com.google.common.base.Preconditions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Integrity-aware JDBC catalogs on in-memory H2, for tests outside this package that need the real
 * atomic publication of a metadata pointer and its integrity hash.
 *
 * <p>The packaged integrity DDL exists for PostgreSQL and MySQL only, so the connection reports
 * PostgreSQL and H2 runs the PostgreSQL script. Everything else is the production catalog.
 */
public final class H2IntegrityJdbcCatalogs {

  private H2IntegrityJdbcCatalogs() {}

  /**
   * Returns the URI of a fresh in-memory H2 database that lives until the JVM exits.
   *
   * @param prefix a readable prefix for the database name
   * @return the JDBC URI
   */
  public static String newUri(String prefix) {
    return String.format(
        "jdbc:h2:mem:%s_%s;DB_CLOSE_DELAY=-1",
        prefix, UUID.randomUUID().toString().replace("-", ""));
  }

  /**
   * Initializes a governed JDBC catalog whose integrity store is provisioned on the database at
   * {@link CatalogProperties#URI}.
   *
   * @param name the catalog name, which is also the {@code catalog_name} of its rows
   * @param properties the catalog properties, including the URI and the warehouse
   * @return the initialized catalog, which reports atomic metadata integrity
   */
  public static JdbcCatalogWithMetadataLocationSupport initialize(
      String name, Map<String, String> properties) {
    JdbcCatalogWithMetadataLocationSupport catalog =
        new JdbcCatalogWithMetadataLocationSupport(
            true, true, catalogProperties -> new PostgreSqlReportingPool(catalogProperties));
    catalog.initialize(name, properties);
    Preconditions.checkState(
        catalog.supportsAtomicMetadataIntegrity(), "integrity store was not provisioned");
    return catalog;
  }

  /**
   * Reads the integrity hash published for a table.
   *
   * @param uri the database URI
   * @param catalogName the catalog name
   * @param identifier the table
   * @return the published hash, or empty when the table has no integrity row
   * @throws SQLException if the database cannot be read
   */
  public static Optional<String> metadataHash(
      String uri, String catalogName, TableIdentifier identifier) throws SQLException {
    String sql =
        String.format(
            "SELECT metadata_hash FROM %s WHERE catalog_name = ? AND table_namespace = ?"
                + " AND table_name = ?",
            JdbcTrustedMetadataStore.TABLE_NAME);
    try (Connection connection = DriverManager.getConnection(uri);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, catalogName);
      statement.setString(2, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(3, identifier.name());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Optional.of(resultSet.getString(1)) : Optional.empty();
      }
    }
  }

  /**
   * Says whether the catalog's pointer table holds a row for a table, read directly so that the
   * answer does not depend on the integrity store.
   *
   * @param uri the database URI
   * @param catalogName the catalog name
   * @param identifier the table
   * @return whether a pointer row exists
   * @throws SQLException if the database cannot be read
   */
  public static boolean pointerExists(String uri, String catalogName, TableIdentifier identifier)
      throws SQLException {
    String sql =
        String.format(
            "SELECT 1 FROM %s WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?",
            JdbcUtil.CATALOG_TABLE_VIEW_NAME);
    try (Connection connection = DriverManager.getConnection(uri);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, catalogName);
      statement.setString(2, JdbcUtil.namespaceToString(identifier.namespace()));
      statement.setString(3, identifier.name());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  /**
   * Drops the integrity table, so that every later publication of an encrypted table fails.
   *
   * @param uri the database URI
   * @throws SQLException if the table cannot be dropped
   */
  public static void dropIntegrityStore(String uri) throws SQLException {
    try (Connection connection = DriverManager.getConnection(uri);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE " + JdbcTrustedMetadataStore.TABLE_NAME);
    }
  }

  /** A client pool whose connections report PostgreSQL as their database product. */
  private static final class PostgreSqlReportingPool extends JdbcClientPool {

    private PostgreSqlReportingPool(Map<String, String> properties) {
      super(properties.get(CatalogProperties.URI), properties);
    }

    @Override
    protected Connection newClient() {
      Connection delegate = super.newClient();
      return (Connection)
          Proxy.newProxyInstance(
              PostgreSqlReportingPool.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if ("getMetaData".equals(method.getName())) {
                  return postgreSqlMetadata(delegate.getMetaData());
                }
                return invoke(method, delegate, arguments);
              });
    }

    private static DatabaseMetaData postgreSqlMetadata(DatabaseMetaData metadata) {
      return (DatabaseMetaData)
          Proxy.newProxyInstance(
              PostgreSqlReportingPool.class.getClassLoader(),
              new Class<?>[] {DatabaseMetaData.class},
              (proxy, method, arguments) ->
                  "getDatabaseProductName".equals(method.getName())
                      ? "PostgreSQL"
                      : invoke(method, metadata, arguments));
    }

    private static Object invoke(Method method, Object target, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
