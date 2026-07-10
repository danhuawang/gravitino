/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
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

  @DeleteProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
