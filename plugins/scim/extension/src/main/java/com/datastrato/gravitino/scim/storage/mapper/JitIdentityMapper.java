/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.JitIdentityBaseSQLProvider;
import com.datastrato.gravitino.scim.storage.po.JitGroupPO;
import com.datastrato.gravitino.scim.storage.po.JitUserGroupRelPO;
import com.datastrato.gravitino.scim.storage.po.JitUserPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** MyBatis mapper for the global JWT-discovered identity tables. */
public interface JitIdentityMapper {
  String JIT_USER_TABLE_NAME = "jit_user_meta";
  String JIT_GROUP_TABLE_NAME = "jit_group_meta";
  String JIT_USER_GROUP_REL_TABLE_NAME = "jit_user_group_rel";

  @SelectProvider(type = JitIdentityBaseSQLProvider.class, method = "selectScimUserIdByName")
  Long selectScimUserIdByName(@Param("userName") String userName);

  @SelectProvider(type = JitIdentityBaseSQLProvider.class, method = "selectScimGroupNamesByNames")
  List<String> selectScimGroupNamesByNames(@Param("groupNames") List<String> groupNames);

  @SelectProvider(type = JitIdentityBaseSQLProvider.class, method = "selectUserByName")
  JitUserPO selectUserByName(@Param("userName") String userName);

  @InsertProvider(type = JitIdentityBaseSQLProvider.class, method = "insertUser")
  int insertUser(@Param("user") JitUserPO user);

  @SelectProvider(type = JitIdentityBaseSQLProvider.class, method = "selectGroupsByNames")
  List<JitGroupPO> selectGroupsByNames(@Param("groupNames") List<String> groupNames);

  @InsertProvider(type = JitIdentityBaseSQLProvider.class, method = "insertGroups")
  int insertGroups(@Param("groups") List<JitGroupPO> groups);

  @SelectProvider(type = JitIdentityBaseSQLProvider.class, method = "selectGroupIdsForUser")
  List<Long> selectGroupIdsForUser(@Param("userId") long userId);

  @UpdateProvider(type = JitIdentityBaseSQLProvider.class, method = "softDeleteMissingMemberships")
  int softDeleteMissingMemberships(
      @Param("userId") long userId,
      @Param("groupIds") List<Long> groupIds,
      @Param("deletedAt") long deletedAt);

  @InsertProvider(type = JitIdentityBaseSQLProvider.class, method = "insertMemberships")
  int insertMemberships(@Param("relations") List<JitUserGroupRelPO> relations);

  @UpdateProvider(type = JitIdentityBaseSQLProvider.class, method = "softDeleteUserByName")
  int softDeleteUserByName(@Param("userName") String userName, @Param("deletedAt") long deletedAt);

  @UpdateProvider(
      type = JitIdentityBaseSQLProvider.class,
      method = "softDeleteMembershipsByUserName")
  int softDeleteMembershipsByUserName(
      @Param("userName") String userName, @Param("deletedAt") long deletedAt);

  @UpdateProvider(type = JitIdentityBaseSQLProvider.class, method = "softDeleteGroupsByNames")
  int softDeleteGroupsByNames(
      @Param("groupNames") List<String> groupNames, @Param("deletedAt") long deletedAt);

  @UpdateProvider(
      type = JitIdentityBaseSQLProvider.class,
      method = "softDeleteMembershipsByGroupNames")
  int softDeleteMembershipsByGroupNames(
      @Param("groupNames") List<String> groupNames, @Param("deletedAt") long deletedAt);

  @DeleteProvider(
      type = JitIdentitySQLProviderFactory.class,
      method = "deleteMembershipsByLegacyTimeline")
  int deleteMembershipsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);

  @DeleteProvider(
      type = JitIdentitySQLProviderFactory.class,
      method = "deleteUsersByLegacyTimeline")
  int deleteUsersByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);

  @DeleteProvider(
      type = JitIdentitySQLProviderFactory.class,
      method = "deleteGroupsByLegacyTimeline")
  int deleteGroupsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
