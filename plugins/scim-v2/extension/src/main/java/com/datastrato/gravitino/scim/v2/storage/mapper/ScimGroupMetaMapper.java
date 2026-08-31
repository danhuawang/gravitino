/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.po.ScimGroupMetaPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for {@code scim_group_meta}. */
public interface ScimGroupMetaMapper {
  String TABLE_NAME = "scim_group_meta";

  @InsertProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "insert")
  void insert(@Param("groupMeta") ScimGroupMetaPO groupMeta);

  @SelectProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "selectByExternalId")
  ScimGroupMetaPO selectByExternalId(@Param("externalId") String externalId);

  @SelectProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "selectByGroupName")
  ScimGroupMetaPO selectByGroupName(@Param("groupName") String groupName);

  @SelectProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "selectByGroupId")
  ScimGroupMetaPO selectByGroupId(@Param("groupId") long groupId);

  @SelectProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "listGroups")
  List<ScimGroupMetaPO> listGroups(@Param("offset") int offset, @Param("limit") int limit);

  @SelectProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "countGroups")
  Long countGroups();

  @UpdateProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "updateExternalId")
  Integer updateExternalId(
      @Param("groupId") long groupId,
      @Param("externalId") String externalId,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion);

  @UpdateProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "softDeleteByExternalId")
  Integer softDeleteByExternalId(@Param("externalId") String externalId);

  @DeleteProvider(type = ScimGroupMetaSQLProviderFactory.class, method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
