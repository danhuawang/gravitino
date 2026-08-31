/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection.mapper.provider.base;

import com.datastrato.gravitino.catalog.connection.mapper.ConnectionTestResultMapper;
import com.datastrato.gravitino.catalog.connection.mapper.po.ConnectionTestResultPO;
import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.mapper.provider.base.CatalogMetaBaseSQLProvider;
import org.apache.ibatis.annotations.Param;

/** Database-neutral SQL provider for Catalog connection test results. */
public class ConnectionTestResultBaseSQLProvider {

  /**
   * Generates SQL to load one result.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @return The select SQL.
   */
  public String select(@Param("catalogId") Long catalogId, @Param("type") String type) {
    return columns() + " WHERE catalog_id = #{catalogId} AND type = #{type}";
  }

  /**
   * Generates SQL to list all results for a Catalog.
   *
   * @param catalogId The Catalog ID.
   * @return The list SQL.
   */
  public String list(@Param("catalogId") Long catalogId) {
    return columns() + " WHERE catalog_id = #{catalogId} ORDER BY type";
  }

  /**
   * Generates SQL to load and lock the Catalog row coordinated with result writes.
   *
   * @param catalogId The Catalog ID.
   * @return The select-for-update SQL.
   */
  public String selectCatalogForUpdate(@Param("catalogId") Long catalogId) {
    return new CatalogMetaBaseSQLProvider().selectCatalogMetaById(catalogId) + " FOR UPDATE";
  }

  /**
   * Generates SQL to insert a result.
   *
   * @param result The result to insert.
   * @return The insert SQL.
   */
  public String insert(@Param("result") ConnectionTestResultPO result) {
    return "INSERT INTO "
        + ConnectionTestResultMapper.TABLE_NAME
        + " (catalog_id, type, catalog_version, test_status, last_tested_at, error_message)"
        + " VALUES (#{result.catalogId}, #{result.type}, #{result.catalogVersion},"
        + " #{result.testStatus}, #{result.lastTestedAt}, #{result.errorMessage})";
  }

  /**
   * Generates SQL to replace an existing result.
   *
   * @param result The replacement result.
   * @return The update SQL.
   */
  public String update(@Param("result") ConnectionTestResultPO result) {
    return "UPDATE "
        + ConnectionTestResultMapper.TABLE_NAME
        + " SET catalog_version = #{result.catalogVersion},"
        + " test_status = #{result.testStatus},"
        + " last_tested_at = #{result.lastTestedAt},"
        + " error_message = #{result.errorMessage}"
        + " WHERE catalog_id = #{result.catalogId} AND type = #{result.type}";
  }

  /**
   * Generates SQL to carry a result to a new Catalog version.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param oldVersion The observed old version.
   * @param newVersion The current new version.
   * @return The update SQL.
   */
  public String updateVersion(
      @Param("catalogId") Long catalogId,
      @Param("type") String type,
      @Param("oldVersion") Long oldVersion,
      @Param("newVersion") Long newVersion) {
    return "UPDATE "
        + ConnectionTestResultMapper.TABLE_NAME
        + " SET catalog_version = #{newVersion}"
        + " WHERE catalog_id = #{catalogId} AND type = #{type}"
        + " AND catalog_version = #{oldVersion}";
  }

  /**
   * Generates SQL to delete a result by type at an observed Catalog version.
   *
   * @param catalogId The Catalog ID.
   * @param type The Catalog or credential connection test type.
   * @param catalogVersion The observed Catalog version.
   * @return The delete SQL.
   */
  public String deleteByTypeAndVersion(
      @Param("catalogId") Long catalogId,
      @Param("type") String type,
      @Param("catalogVersion") Long catalogVersion) {
    return "DELETE FROM "
        + ConnectionTestResultMapper.TABLE_NAME
        + " WHERE catalog_id = #{catalogId} AND type = #{type}"
        + " AND catalog_version = #{catalogVersion}";
  }

  /**
   * Generates SQL to delete results whose Catalog no longer exists as a live relational entity.
   *
   * @param limit The maximum number of orphaned Catalog IDs to process.
   * @return The orphan cleanup SQL.
   */
  public String deleteOrphaned(@Param("limit") int limit) {
    return "DELETE FROM "
        + ConnectionTestResultMapper.TABLE_NAME
        + " WHERE catalog_id IN (SELECT catalog_id FROM ("
        + "SELECT DISTINCT result.catalog_id FROM "
        + ConnectionTestResultMapper.TABLE_NAME
        + " result WHERE NOT EXISTS (SELECT 1 FROM "
        + CatalogMetaMapper.TABLE_NAME
        + " catalog WHERE catalog.catalog_id = result.catalog_id AND catalog.deleted_at = 0)"
        + " LIMIT #{limit}) orphan_ids)";
  }

  private String columns() {
    return "SELECT catalog_id AS catalogId, type, catalog_version AS catalogVersion,"
        + " test_status AS testStatus, last_tested_at AS lastTestedAt,"
        + " error_message AS errorMessage FROM "
        + ConnectionTestResultMapper.TABLE_NAME;
  }
}
