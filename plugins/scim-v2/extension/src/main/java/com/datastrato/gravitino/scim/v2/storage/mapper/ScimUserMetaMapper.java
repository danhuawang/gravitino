/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.po.ScimUserMetaPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for {@code v2_scim_user_meta}. */
public interface ScimUserMetaMapper {
  String TABLE_NAME = "v2_scim_user_meta";

  @InsertProvider(type = ScimUserMetaSQLProviderFactory.class, method = "insert")
  void insert(@Param("userMeta") ScimUserMetaPO userMeta);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByExternalId")
  ScimUserMetaPO selectByExternalId(@Param("externalId") String externalId);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByUserName")
  ScimUserMetaPO selectByUserName(@Param("userName") String userName);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "selectByUserId")
  ScimUserMetaPO selectByUserId(@Param("userId") long userId);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "listUsers")
  List<ScimUserMetaPO> listUsers(@Param("offset") int offset, @Param("limit") int limit);

  @SelectProvider(type = ScimUserMetaSQLProviderFactory.class, method = "countUsers")
  Long countUsers();

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "updateEnabled")
  Integer updateEnabled(@Param("externalId") String externalId, @Param("enabled") boolean enabled);

  @UpdateProvider(type = ScimUserMetaSQLProviderFactory.class, method = "softDeleteByExternalId")
  Integer softDeleteByExternalId(@Param("externalId") String externalId);

  @DeleteProvider(type = ScimUserMetaSQLProviderFactory.class, method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
