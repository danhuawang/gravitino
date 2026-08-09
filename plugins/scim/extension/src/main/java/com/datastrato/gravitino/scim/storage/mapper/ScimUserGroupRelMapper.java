/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.util.List;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/**
 * A MyBatis mapper for SCIM user-group membership operations on {@code scim_user_group_rel}.
 *
 * <p>SQL statements are provided through {@code *Provider} annotations on this mapper interface.
 */
public interface ScimUserGroupRelMapper {
  String SCIM_USER_GROUP_REL_TABLE_NAME = "scim_user_group_rel";

  @SelectProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "selectMembersByGroupId")
  List<ScimGroupMemberPO> selectMembersByGroupId(
      @Param("metalakeName") String metalakeName, @Param("groupId") long groupId);

  @SelectProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "selectGroupNamesByUsername")
  List<String> selectGroupNamesByUsername(
      @Param("username") String username, @Param("metalakeName") String metalakeName);

  /**
   * Inserts group memberships by resolving Gravitino ids from {@code group_meta} and {@code
   * user_meta}.
   *
   * <p>{@code userIds} must be non-empty; callers should skip this method when there are no members
   * to add.
   *
   * @return number of membership rows affected
   */
  @InsertProvider(type = ScimUserGroupRelSQLProviderFactory.class, method = "insertMemberships")
  int insertMemberships(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion);

  /**
   * Soft-deletes all active memberships for a user identified by Gravitino user id.
   *
   * @param metalakeName target metalake name
   * @param userId Gravitino user id
   */
  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByUserId")
  void softDeleteMembersByUserId(
      @Param("metalakeName") String metalakeName, @Param("userId") long userId);

  /**
   * Soft-deletes group memberships for users identified by Gravitino user ids.
   *
   * <p>{@code userIds} must be non-empty; callers should skip this method when there are no members
   * to remove.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH {@code members[].value}
   */
  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByGroupAndUserIds")
  void softDeleteMembersByGroupAndUserIds(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds);

  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByUnavailableMetalake")
  Integer softDeleteMembersByUnavailableMetalake();

  /**
   * Soft-deletes all active memberships for a group identified by Gravitino group id.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   */
  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByGroupId")
  void softDeleteMembersByGroupId(
      @Param("metalakeName") String metalakeName, @Param("groupId") long groupId);

  /**
   * Updates one active membership row from {@code oldUserId} to {@code newUserId}.
   *
   * <p>Requires {@code newUserId} to exist in {@code user_meta}. Returns {@code 0} when the old
   * member is missing, the replacement user is missing, or the replacement is already a member.
   *
   * @return number of membership rows updated
   */
  @UpdateProvider(type = ScimUserGroupRelSQLProviderFactory.class, method = "updateMemberUserId")
  int updateMemberUserId(
      @Param("metalakeName") String metalakeName,
      @Param("groupId") long groupId,
      @Param("oldUserId") long oldUserId,
      @Param("newUserId") long newUserId,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion);

  @DeleteProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
