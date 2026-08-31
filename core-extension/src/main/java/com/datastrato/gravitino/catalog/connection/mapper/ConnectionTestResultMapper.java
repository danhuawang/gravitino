/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection.mapper;

import com.datastrato.gravitino.catalog.connection.mapper.po.ConnectionTestResultPO;
import java.util.List;
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for the Enterprise Catalog connection test result table. */
public interface ConnectionTestResultMapper {

  /** The relational table name. */
  String TABLE_NAME = "catalog_connection_test_meta";

  /**
   * Loads one connection test result.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @return The stored result, or {@code null}.
   */
  @SelectProvider(type = ConnectionTestResultSQLProviderFactory.class, method = "select")
  ConnectionTestResultPO select(@Param("catalogId") Long catalogId, @Param("type") String type);

  /**
   * Lists every result stored for a Catalog.
   *
   * @param catalogId The Catalog ID.
   * @return All stored connection test types for the Catalog.
   */
  @SelectProvider(type = ConnectionTestResultSQLProviderFactory.class, method = "list")
  List<ConnectionTestResultPO> list(@Param("catalogId") Long catalogId);

  /**
   * Loads and locks the Catalog row coordinated with connection test result writes.
   *
   * @param catalogId The Catalog ID.
   * @return The active Catalog row, or {@code null}.
   */
  @SelectProvider(
      type = ConnectionTestResultSQLProviderFactory.class,
      method = "selectCatalogForUpdate")
  CatalogPO selectCatalogForUpdate(@Param("catalogId") Long catalogId);

  /**
   * Inserts a connection test result.
   *
   * @param result The result to insert.
   */
  @InsertProvider(type = ConnectionTestResultSQLProviderFactory.class, method = "insert")
  void insert(@Param("result") ConnectionTestResultPO result);

  /**
   * Replaces an existing connection test result.
   *
   * @param result The replacement result.
   * @return The updated row count.
   */
  @UpdateProvider(type = ConnectionTestResultSQLProviderFactory.class, method = "update")
  int update(@Param("result") ConnectionTestResultPO result);

  /**
   * Carries a result to a new Catalog version when it still references the observed old version.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param oldVersion The observed old Catalog version.
   * @param newVersion The current new Catalog version.
   * @return The updated row count.
   */
  @UpdateProvider(type = ConnectionTestResultSQLProviderFactory.class, method = "updateVersion")
  int updateVersion(
      @Param("catalogId") Long catalogId,
      @Param("type") String type,
      @Param("oldVersion") Long oldVersion,
      @Param("newVersion") Long newVersion);

  /**
   * Deletes a result by type only while it references the observed Catalog version.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param catalogVersion The observed Catalog version.
   * @return The deleted row count.
   */
  @DeleteProvider(
      type = ConnectionTestResultSQLProviderFactory.class,
      method = "deleteByTypeAndVersion")
  int deleteByTypeAndVersion(
      @Param("catalogId") Long catalogId,
      @Param("type") String type,
      @Param("catalogVersion") Long catalogVersion);

  /**
   * Deletes results whose Catalog no longer exists as a live relational entity.
   *
   * @param limit The maximum number of orphaned Catalog IDs to process.
   * @return The number of deleted connection test result rows.
   */
  @DeleteProvider(type = ConnectionTestResultSQLProviderFactory.class, method = "deleteOrphaned")
  int deleteOrphaned(@Param("limit") int limit);
}
