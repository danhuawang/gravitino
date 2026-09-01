/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.po.ScimProvisioningStatsPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for {@code v2_scim_token_meta}. */
public interface ScimTokenMetaMapper {
  String TABLE_NAME = "v2_scim_token_meta";

  @InsertProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "insert")
  void insert(@Param("tokenMeta") ScimTokenMetaPO tokenMeta);

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "selectByTokenHash")
  ScimTokenMetaPO selectByTokenHash(@Param("tokenHash") String tokenHash);

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "selectByName")
  ScimTokenMetaPO selectByName(@Param("tokenName") String tokenName);

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "listProvisioningStats")
  ScimProvisioningStatsPO listProvisioningStats();

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "listAll")
  List<ScimTokenMetaPO> listAll();

  @SelectProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "selectMaxLastUsedAt")
  Long selectMaxLastUsedAt();

  @UpdateProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "softDeleteByName")
  Integer softDeleteByName(@Param("tokenName") String tokenName);

  @UpdateProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "softDeleteByExpiration")
  Integer softDeleteByExpiration();

  @UpdateProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "updateTokenOnRotate")
  Integer updateTokenOnRotate(
      @Param("newTokenMeta") ScimTokenMetaPO newTokenMeta,
      @Param("oldTokenMeta") ScimTokenMetaPO oldTokenMeta);

  @UpdateProvider(
      type = ScimTokenMetaSQLProviderFactory.class,
      method = "updateScimTokenLastUsedAt")
  Integer updateScimTokenLastUsedAt(@Param("tokenId") Long tokenId);

  @DeleteProvider(type = ScimTokenMetaSQLProviderFactory.class, method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
