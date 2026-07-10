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
      method = "selectMembersByGroupExternalId")
  List<ScimGroupMemberPO> selectMembersByGroupExternalId(
      @Param("metalakeName") String metalakeName, @Param("groupExternalId") String groupExternalId);

  @SelectProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "selectGroupNamesByUsername")
  List<String> selectGroupNamesByUsername(
      @Param("username") String username, @Param("metalakeName") String metalakeName);

  /**
   * Inserts group memberships by resolving SCIM ids from {@code group_meta} and {@code user_meta}.
   *
   * <p>{@code userExternalIds} must be non-empty; callers should skip this method when there are no
   * members to add.
   *
   * @return number of membership rows affected
   */
  @InsertProvider(type = ScimUserGroupRelSQLProviderFactory.class, method = "insertMemberships")
  int insertMemberships(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId,
      @Param("userExternalIds") List<String> userExternalIds,
      @Param("auditInfo") String auditInfo,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion);

  /**
   * Soft-deletes all active memberships for a user identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param userExternalId SCIM user {@code externalId}
   */
  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByUserExternalId")
  void softDeleteMembersByUserExternalId(
      @Param("metalakeName") String metalakeName, @Param("userExternalId") String userExternalId);

  /**
   * Soft-deletes group memberships for users identified by SCIM {@code externalId}s.
   *
   * <p>{@code userExternalIds} must be non-empty; callers should skip this method when there are no
   * members to remove.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH {@code members[].value}
   */
  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByGroupAndUserExternalIds")
  void softDeleteMembersByGroupAndUserExternalIds(
      @Param("metalakeName") String metalakeName,
      @Param("groupExternalId") String groupExternalId,
      @Param("userExternalIds") List<String> userExternalIds);

  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByUnavailableMetalake")
  Integer softDeleteMembersByUnavailableMetalake();

  /**
   * Soft-deletes all active memberships for a group identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   */
  @UpdateProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "softDeleteMembersByGroupExternalId")
  void softDeleteMembersByGroupExternalId(
      @Param("metalakeName") String metalakeName, @Param("groupExternalId") String groupExternalId);

  @DeleteProvider(
      type = ScimUserGroupRelSQLProviderFactory.class,
      method = "deleteByLegacyTimeline")
  Integer deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit);
}
