/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.po.DirectoryGroupPO;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.authorization.po.IdpUserIdPO;
import java.util.List;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Enterprise MyBatis mapper for group_meta reads with built-in IdP origin checks. */
public interface DatastratoGroupMetaMapper {

  String IDP_GROUP_TABLE_NAME = "idp_group_meta";
  String IDP_USER_GROUP_REL_TABLE_NAME = "idp_user_group_rel";
  String SCIM_GROUP_TABLE_NAME = "scim_group_meta";
  String SCIM_USER_GROUP_REL_TABLE_NAME = "scim_user_group_rel";

  /**
   * Lists active IdP group names with whether each is already in the metalake.
   *
   * @param metalakeName The metalake name.
   * @return JOIN rows. Empty when the metalake is missing.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "listGroupsWithMetalakeStatus")
  List<IdpNameStatusPO> listGroupsWithMetalakeStatus(@Param("metalakeName") String metalakeName);

  /**
   * Lists metalake groups with roles and built-in IdP membership in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @return JOIN rows.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "listGroupsByMetalakeWithOrigin")
  List<IdpNameStatusPO.GroupWithOrigin> listGroupsByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName);

  /**
   * Loads one metalake group with roles, identity-store origin, and {@code userCount} in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param groupName The group name.
   * @return The JOIN row, or {@code null} when missing.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "getGroupByMetalakeWithOrigin")
  IdpNameStatusPO.GroupWithOrigin getGroupByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupName") String groupName);

  /**
   * Loads metalake groups by name with roles and built-in IdP membership in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param groupNames Group names to load.
   * @return JOIN rows for groups present in the metalake.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "listGroupsByMetalakeAndNamesWithOrigin")
  List<IdpNameStatusPO.GroupWithOrigin> listGroupsByMetalakeAndNamesWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupNames") List<String> groupNames);

  /**
   * Lists metalake groups for a user with roles and built-in IdP membership in one JOIN.
   *
   * <p>Local users resolve membership from {@code idp_user_group_rel}. Provisioned users resolve
   * membership from {@code scim_user_group_rel}. Returns no rows when the user is missing; one row
   * with a null {@code groupId} when the user exists but belongs to no metalake groups.
   *
   * @param metalakeName The metalake name.
   * @param userName The username.
   * @return JOIN rows.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "listGroupsForMetalakeUserWithOrigin")
  List<IdpNameStatusPO.GroupWithOrigin> listGroupsForMetalakeUserWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userName") String userName);

  /**
   * Loads metalake group totals and empty-group count in one query against {@code group_meta}.
   *
   * @param metalakeName The metalake name.
   * @return One aggregate row, or {@code null} when the query returns no row.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "countGroupsWithEmptyByMetalake")
  IdpNameStatusPO.GroupMembershipCountsRow countGroupsWithEmptyByMetalake(
      @Param("metalakeName") String metalakeName);

  /**
   * Lists identity-store groups for the Directory Groups page.
   *
   * <p>Rows come from {@code idp_group_meta} (Local) and {@code scim_group_meta} (Provisioned).
   * When a group name exists in both, the IdP / Local row wins. Includes identity-store member
   * counts and metalake membership names.
   *
   * @return Directory group rows ordered by group name.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "listDirectoryGroups")
  List<DirectoryGroupPO> listDirectoryGroups();

  /**
   * Returns group names that have an active row in {@code idp_group_meta}.
   *
   * @param groupNames Group names to check.
   * @return Group names present in the requested set.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "selectIdpGroupNamesByNames")
  List<String> selectIdpGroupNamesByNames(@Param("groupNames") List<String> groupNames);

  /**
   * Returns active IdP user ids for the given names.
   *
   * @param userNames Usernames to resolve.
   * @return User name / id rows present in {@code idp_user_meta}.
   */
  @SelectProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "selectIdpUserIdsByNames")
  List<IdpUserIdPO> selectIdpUserIdsByNames(@Param("userNames") List<String> userNames);

  /**
   * Inserts a Local Directory Group into {@code idp_group_meta}.
   *
   * @param groupId Generated group id.
   * @param groupName Group name.
   * @param groupComment Group comment.
   * @return Number of rows inserted.
   */
  @InsertProvider(type = DatastratoGroupMetaSQLProviderFactory.class, method = "insertIdpGroup")
  int insertIdpGroup(
      @Param("groupId") long groupId,
      @Param("groupName") String groupName,
      @Param("groupComment") String groupComment);

  /**
   * Batch-inserts {@code idp_user_group_rel} rows for a new Directory Group.
   *
   * @param relations Relation rows.
   * @return Number of rows inserted.
   */
  @InsertProvider(
      type = DatastratoGroupMetaSQLProviderFactory.class,
      method = "batchInsertIdpUserGroupRels")
  int batchInsertIdpUserGroupRels(@Param("relations") List<IdpUserGroupRelInsertPO> relations);
}
