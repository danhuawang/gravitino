/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.po.DirectoryUserPO;
import com.datastrato.gravitino.authorization.po.IdpGroupIdPO;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.authorization.po.UserWithGroupsPO;
import java.util.List;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** Enterprise MyBatis mapper for user_meta reads/updates and built-in IdP origin checks. */
public interface DatastratoUserMetaMapper {

  String IDP_USER_TABLE_NAME = "idp_user_meta";
  String IDP_USER_GROUP_REL_TABLE_NAME = "idp_user_group_rel";
  String SCIM_USER_TABLE_NAME = "scim_user_meta";
  String SCIM_USER_GROUP_REL_TABLE_NAME = "scim_user_group_rel";

  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUserMetasByMetalakeNameAndNames")
  List<UserPO> listUserMetasByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames);

  @UpdateProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "batchUpdateEnabledByMetalakeNameAndNames")
  int batchUpdateEnabledByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName,
      @Param("userNames") List<String> userNames,
      @Param("enabled") boolean enabled);

  /**
   * Returns usernames that have an active row in {@code scim_user_meta}.
   *
   * @param userNames Usernames to check.
   * @return Provisioned usernames present in the requested set.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "selectScimUserNamesByNames")
  List<String> selectScimUserNamesByNames(@Param("userNames") List<String> userNames);

  /**
   * Batch-updates {@code enabled} for provisioned users in {@code scim_user_meta}.
   *
   * @param userNames Distinct usernames.
   * @param enabled Target enabled value.
   * @return Number of rows updated.
   */
  @UpdateProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "batchUpdateScimUserEnabledByUserNames")
  int batchUpdateScimUserEnabledByUserNames(
      @Param("userNames") List<String> userNames, @Param("enabled") boolean enabled);

  /**
   * Returns usernames that have an active row in {@code idp_user_meta}.
   *
   * @param userNames Usernames to check.
   * @return Local usernames present in the requested set.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "selectIdpUserNamesByNames")
  List<String> selectIdpUserNamesByNames(@Param("userNames") List<String> userNames);

  /**
   * Batch-updates {@code enabled} for Local Directory Users in {@code idp_user_meta}.
   *
   * @param userNames Distinct usernames.
   * @param enabled Target enabled value.
   * @return Number of rows updated.
   */
  @UpdateProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "batchUpdateIdpUserEnabledByUserNames")
  int batchUpdateIdpUserEnabledByUserNames(
      @Param("userNames") List<String> userNames, @Param("enabled") boolean enabled);

  /**
   * Returns active IdP group ids for the given names.
   *
   * @param groupNames Group names to resolve.
   * @return Group name / id rows present in {@code idp_group_meta}.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "selectIdpGroupIdsByNames")
  List<IdpGroupIdPO> selectIdpGroupIdsByNames(@Param("groupNames") List<String> groupNames);

  /**
   * Inserts a Local Directory User into {@code idp_user_meta}.
   *
   * @param userId Generated user id.
   * @param userName Username.
   * @param passwordHash Hashed password.
   * @param enabled Whether the user is enabled.
   * @return Number of rows inserted.
   */
  @InsertProvider(type = DatastratoUserMetaSQLProviderFactory.class, method = "insertIdpUser")
  int insertIdpUser(
      @Param("userId") long userId,
      @Param("userName") String userName,
      @Param("passwordHash") String passwordHash,
      @Param("enabled") boolean enabled);

  /**
   * Batch-inserts {@code idp_user_group_rel} rows for a new Directory User.
   *
   * @param relations Relation rows.
   * @return Number of rows inserted.
   */
  @InsertProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "batchInsertIdpUserGroupRels")
  int batchInsertIdpUserGroupRels(@Param("relations") List<IdpUserGroupRelInsertPO> relations);

  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUsersWithMetalakeStatus")
  List<IdpNameStatusPO> listUsersWithMetalakeStatus(@Param("metalakeName") String metalakeName);

  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUsersByMetalakeWithOrigin")
  List<IdpNameStatusPO.UserWithOrigin> listUsersByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName);

  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "getUserByMetalakeWithOrigin")
  IdpNameStatusPO.UserWithOrigin getUserByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userName") String userName);

  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUsersByMetalakeAndNamesWithOrigin")
  List<IdpNameStatusPO.UserWithOrigin> listUsersByMetalakeAndNamesWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames);

  /**
   * Lists metalake users in a group with roles and built-in IdP membership in one JOIN.
   *
   * <p>Local groups resolve membership from {@code idp_user_group_rel}. Provisioned groups resolve
   * membership from {@code scim_user_group_rel}. Returns no rows when the group is missing; one row
   * with a null {@code userId} when the group exists but has no metalake members.
   *
   * @param metalakeName The metalake name.
   * @param groupName The group name.
   * @return JOIN rows.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUsersForMetalakeGroupWithOrigin")
  List<IdpNameStatusPO.UserWithOrigin> listUsersForMetalakeGroupWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupName") String groupName);

  /**
   * Loads metalake user totals split by {@code enabled} in one query against {@code user_meta}.
   *
   * @param metalakeName The metalake name.
   * @return One aggregate row, or {@code null} when the query returns no row.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "countUsersByEnabledByMetalake")
  IdpNameStatusPO.UserEnabledCountsRow countUsersByEnabledByMetalake(
      @Param("metalakeName") String metalakeName);

  /**
   * Lists metalake users with roles, group names, and built-in IdP membership in one query.
   *
   * @param metalakeName The metalake name.
   * @return User rows with aggregated group names.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUserWithGroupsPOsByMetalakeName")
  List<UserWithGroupsPO> listUserWithGroupsPOsByMetalakeName(
      @Param("metalakeName") String metalakeName);

  /**
   * Lists identity-store users for the Directory Users page.
   *
   * <p>Rows come from {@code idp_user_meta} (Local) and {@code scim_user_meta} (Provisioned). When
   * a username exists in both, the IdP / Local row wins. Includes identity-store groups and
   * metalake membership names.
   *
   * @return Directory user rows ordered by username.
   */
  @SelectProvider(type = DatastratoUserMetaSQLProviderFactory.class, method = "listDirectoryUsers")
  List<DirectoryUserPO> listDirectoryUsers();
}
