/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection.mapper;

import com.datastrato.gravitino.catalog.connection.mapper.po.ConnectionTestResultPO;
import com.datastrato.gravitino.catalog.connection.mapper.provider.base.ConnectionTestResultBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for Catalog connection test results. */
public final class ConnectionTestResultSQLProviderFactory {
  private static final Map<JDBCBackendType, ConnectionTestResultBaseSQLProvider> PROVIDERS =
      ImmutableMap.of(
          JDBCBackendType.MYSQL, new ConnectionTestResultBaseSQLProvider(),
          JDBCBackendType.H2, new ConnectionTestResultBaseSQLProvider(),
          JDBCBackendType.POSTGRESQL, new ConnectionTestResultBaseSQLProvider());

  private ConnectionTestResultSQLProviderFactory() {}

  /**
   * Returns SQL to load one result.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @return SQL to load one result.
   */
  public static String select(@Param("catalogId") Long catalogId, @Param("type") String type) {
    return provider().select(catalogId, type);
  }

  /**
   * Returns SQL to list every result for a Catalog.
   *
   * @param catalogId The Catalog ID.
   * @return SQL to list every result for a Catalog.
   */
  public static String list(@Param("catalogId") Long catalogId) {
    return provider().list(catalogId);
  }

  /**
   * Returns SQL to load and lock the Catalog row coordinated with result writes.
   *
   * @param catalogId The Catalog ID.
   * @return SQL to load and lock the active Catalog row.
   */
  public static String selectCatalogForUpdate(@Param("catalogId") Long catalogId) {
    return provider().selectCatalogForUpdate(catalogId);
  }

  /**
   * Returns SQL to insert a result.
   *
   * @param result The connection test result.
   * @return SQL to insert a result.
   */
  public static String insert(@Param("result") ConnectionTestResultPO result) {
    return provider().insert(result);
  }

  /**
   * Returns SQL to replace a result.
   *
   * @param result The connection test result.
   * @return SQL to replace a result.
   */
  public static String update(@Param("result") ConnectionTestResultPO result) {
    return provider().update(result);
  }

  /**
   * Returns SQL to carry a result to a new Catalog version.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param oldVersion The observed Catalog version.
   * @param newVersion The current Catalog version.
   * @return SQL to carry a result to a new Catalog version.
   */
  public static String updateVersion(
      @Param("catalogId") Long catalogId,
      @Param("type") String type,
      @Param("oldVersion") Long oldVersion,
      @Param("newVersion") Long newVersion) {
    return provider().updateVersion(catalogId, type, oldVersion, newVersion);
  }

  /**
   * Returns SQL to delete one result by type at an observed Catalog version.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param catalogVersion The observed Catalog version.
   * @return SQL to delete one result by type at an observed Catalog version.
   */
  public static String deleteByTypeAndVersion(
      @Param("catalogId") Long catalogId,
      @Param("type") String type,
      @Param("catalogVersion") Long catalogVersion) {
    return provider().deleteByTypeAndVersion(catalogId, type, catalogVersion);
  }

  /**
   * Returns SQL to delete results whose Catalog no longer exists as a live relational entity.
   *
   * @param limit The maximum number of orphaned Catalog IDs to process.
   * @return SQL to delete orphaned connection test results.
   */
  public static String deleteOrphaned(@Param("limit") int limit) {
    return provider().deleteOrphaned(limit);
  }

  private static ConnectionTestResultBaseSQLProvider provider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }
}
