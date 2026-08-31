/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Enterprise MyBatis mapper for group_meta reads with built-in IdP origin checks. */
public interface DatastratoGroupMetaMapper {

  String IDP_GROUP_TABLE_NAME = "idp_group_meta";
  String IDP_USER_GROUP_REL_TABLE_NAME = "idp_user_group_rel";
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
}
