/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import com.datastrato.gravitino.scim.v2.storage.po.ScimGroupMemberPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for {@code scim_user_group_rel}. */
public interface ScimUserGroupRelMapper {
  String SCIM_USER_GROUP_REL_TABLE_NAME = "scim_user_group_rel";

  @SelectProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "selectMembersByGroupId")
  List<ScimGroupMemberPO> selectMembersByGroupId(@Param("groupId") long groupId);

  @SelectProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "selectGroupNamesByUsername")
  List<String> selectGroupNamesByUsername(@Param("username") String username);

  @InsertProvider(type = ScimUserGroupRelSQLProviderFactory.class, method = "insertMemberships")
  int insertMemberships(
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion);

  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByUserId")
  void softDeleteMembersByUserId(@Param("userId") long userId);

  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByGroupAndUserIds")
  void softDeleteMembersByGroupAndUserIds(
      @Param("groupId") long groupId, @Param("userIds") List<Long> userIds);

  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByGroupId")
  void softDeleteMembersByGroupId(@Param("groupId") long groupId);

  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteOrphanMemberships")
  Integer softDeleteOrphanMemberships();

  @UpdateProvider(type = ScimUserGroupRelSQLProviderFactory.class, method = "updateMemberUserId")
  int updateMemberUserId(
      @Param("groupId") long groupId,
      @Param("oldUserId") long oldUserId,
      @Param("newUserId") long newUserId,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion);

  @DeleteProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
