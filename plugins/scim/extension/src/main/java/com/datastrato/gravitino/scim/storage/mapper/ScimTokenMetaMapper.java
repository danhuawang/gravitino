/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.po.ScimProvisioningStatsPO;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/**
 * A MyBatis mapper for SCIM token metadata operations.
 *
 * <p>This interface defines the SQL statements MyBatis executes for the SCIM token metadata store.
 * The SQLs are provided through {@code *Provider} annotations on this mapper interface.
 */
public interface ScimTokenMetaMapper {
  String TABLE_NAME = "scim_token_meta";

  @InsertProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "insert")
  void insert(@Param("tokenMeta") ScimTokenMetaPO tokenMeta);

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "selectByTokenHash")
  ScimTokenMetaPO selectByTokenHash(@Param("tokenHash") String tokenHash);

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "selectByMetalakeAndName")
  ScimTokenMetaPO selectByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName);

  /**
   * Lists SCIM provisioning stats for the given metalake ids, including zero-token metalakes.
   *
   * @param metalakeIds metalake ids to include
   * @return stats rows ordered by metalake name
   */
  @SelectProvider(
      type = ScimTokenMetaSQLProviderFactory.class,
      method = "listProvisioningStatsByMetalakeIds")
  List<ScimProvisioningStatsPO> listProvisioningStatsByMetalakeIds(
      @Param("metalakeIds") List<Long> metalakeIds);

  /**
   * Lists active token rows for a metalake ordered by token name.
   *
   * @param metalakeName target metalake name
   * @return active token rows
   */
  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "listByMetalake")
  List<ScimTokenMetaPO> listByMetalake(@Param("metalakeName") String metalakeName);

  /**
   * Returns the latest {@code last_used_at} among active tokens for a metalake.
   *
   * @param metalakeName target metalake name
   * @return max last used epoch millis, or {@code 0} when none
   */
  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "selectMaxLastUsedAt")
  Long selectMaxLastUsedAt(@Param("metalakeName") String metalakeName);

  @UpdateProvider(
      type = ScimTokenMetaSQLProviderFactory.class,
      method = "softDeleteByMetalakeAndName")
  Integer softDeleteByMetalakeAndName(
      @Param("metalakeName") String metalakeName, @Param("tokenName") String tokenName);

  @UpdateProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "softDeleteByExpiration")
  Integer softDeleteByExpiration();

  @UpdateProvider(
      type = ScimTokenMetaSQLProviderFactory.class,
      method = "softDeleteByUnavailableMetalake")
  Integer softDeleteByUnavailableMetalake();

  @UpdateProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "updateTokenOnRotate")
  Integer updateTokenOnRotate(
      @Param("newTokenMeta") ScimTokenMetaPO newTokenMeta,
      @Param("oldTokenMeta") ScimTokenMetaPO oldTokenMeta);

  /**
   * Updates {@code last_used_at} for an active token after authenticated SCIM access.
   *
   * @param tokenId token id
   * @return number of updated rows
   */
  @UpdateProvider(
      type = ScimTokenMetaSQLProviderFactory.class,
      method = "updateScimTokenLastUsedAt")
  Integer updateScimTokenLastUsedAt(@Param("tokenId") Long tokenId);

  @DeleteProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
