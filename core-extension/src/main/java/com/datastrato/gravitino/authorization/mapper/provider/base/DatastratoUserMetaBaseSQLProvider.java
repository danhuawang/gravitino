/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.RoleMetaMapper.ROLE_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_TABLE_NAME;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.ibatis.annotations.Param;

/**
 * Base SQL for enterprise user_meta reads/updates and built-in IdP origin checks.
 *
 * <p>MySQL, H2, and PostgreSQL share the same statements.
 */
public class DatastratoUserMetaBaseSQLProvider {

  /**
   * Lists active users under a metalake by name.
   *
   * @param metalakeName The metalake name.
   * @param userNames Distinct user names.
   * @return MyBatis script SQL.
   */
  public String listUserMetasByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames) {
    return "<script>"
        + "SELECT user_id as userId, user_name as userName,"
        + " metalake_id as metalakeId,"
        + " external_id as externalId, enabled as enabled,"
        + " audit_info as auditInfo, current_version as currentVersion,"
        + " last_version as lastVersion, deleted_at as deletedAt"
        + " FROM "
        + USER_TABLE_NAME
        + " WHERE deleted_at = 0"
        + " AND metalake_id = "
        + metalakeIdByNameSubquery()
        + " AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Builds a batch UPDATE for users that already passed validation.
   *
   * @param metalakeName The metalake name.
   * @param userNames Distinct user names.
   * @param enabled Target enabled value.
   * @return MyBatis script SQL.
   */
  public String batchUpdateEnabledByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName,
      @Param("userNames") List<String> userNames,
      @Param("enabled") boolean enabled) {
    return "<script>"
        + "UPDATE "
        + USER_TABLE_NAME
        + " SET enabled = #{enabled},"
        + " last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE deleted_at = 0"
        + " AND metalake_id = "
        + metalakeIdByNameSubquery()
        + " AND external_id IS NULL"
        + " AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Lists active IdP usernames with whether each is already in the metalake.
   *
   * @param metalakeName The metalake name.
   * @return JOIN SQL.
   */
  public String listUsersWithMetalakeStatus(@Param("metalakeName") String metalakeName) {
    return "SELECT iu.user_name AS name,"
        + " CASE WHEN ut.user_name IS NOT NULL THEN 1 ELSE 0 END AS status"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu ON iu.deleted_at = 0 LEFT JOIN "
        + USER_TABLE_NAME
        + " ut ON ut.user_name = iu.user_name AND ut.deleted_at = 0"
        + " AND ut.metalake_id = mt.metalake_id"
        + " WHERE mt.metalake_name = #{metalakeName} AND mt.deleted_at = 0"
        + " ORDER BY iu.user_name";
  }

  /**
   * Loads a metalake user with roles and built-in IdP membership in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param userName The username.
   * @return JOIN SQL.
   */
  public String getUserByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userName") String userName) {
    return usersWithOriginSelectAndFrom(true, " AND ut.user_name = #{userName}")
        + " GROUP BY ut.user_id";
  }

  /**
   * Lists metalake users with roles and whether each name exists in the built-in IdP.
   *
   * @param metalakeName The metalake name.
   * @return JOIN SQL.
   */
  public String listUsersByMetalakeWithOrigin(@Param("metalakeName") String metalakeName) {
    return usersWithOriginSelectAndFrom(false, null) + " GROUP BY ut.user_id";
  }

  /**
   * Loads metalake users by name with roles and built-in IdP membership in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param userNames Usernames to load.
   * @return JOIN SQL.
   */
  public String listUsersByMetalakeAndNamesWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames) {
    return "<script>"
        + usersWithOriginSelectAndFrom(true, " AND ut.user_name IN " + userNameInClause())
        + " GROUP BY ut.user_id"
        + "</script>";
  }

  /**
   * Lists metalake users in a group with roles and built-in IdP membership in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param groupName The group name.
   * @return JOIN SQL.
   */
  public String listUsersForMetalakeGroupWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupName") String groupName) {
    return usersForMetalakeGroupSelectAndFrom() + " GROUP BY ut.user_id, gt.group_id";
  }

  /**
   * Lists metalake users with roles, group names, and built-in IdP membership in one query.
   *
   * @param metalakeName The metalake name.
   * @return MyBatis SQL.
   */
  public String listUserWithGroupsPOsByMetalakeName(@Param("metalakeName") String metalakeName) {
    return "SELECT ut.user_id as userId, ut.user_name as userName,"
        + " ut.metalake_id as metalakeId,"
        + " ut.external_id as externalId, ut.enabled as enabled,"
        + " ut.audit_info as auditInfo,"
        + " ut.current_version as currentVersion, ut.last_version as lastVersion,"
        + " ut.deleted_at as deletedAt,"
        + " roles.roleNames as roleNames,"
        + " roles.roleIds as roleIds,"
        + " userGroups.groupNames as groupNames,"
        + " CASE WHEN iu.user_name IS NOT NULL THEN 1 ELSE 0 END as inBuiltInIdp"
        + " FROM "
        + USER_TABLE_NAME
        + " ut JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt ON ut.metalake_id = mt.metalake_id AND mt.deleted_at = 0 AND mt.metalake_name ="
        + " #{metalakeName}"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu ON iu.user_name = ut.user_name AND iu.deleted_at = 0"
        + " LEFT OUTER JOIN ("
        + roleAggregationSubquery()
        + ") roles ON roles.userId = ut.user_id"
        + " LEFT OUTER JOIN ("
        + groupAggregationSubquery()
        + ") userGroups ON userGroups.userId = ut.user_id"
        + " WHERE ut.deleted_at = 0";
  }

  /**
   * Loads metalake user totals split by {@code enabled}.
   *
   * @param metalakeName The metalake name.
   * @return Aggregate SQL returning one row.
   */
  public String countUsersByEnabledByMetalake(@Param("metalakeName") String metalakeName) {
    return "SELECT COUNT(*) AS total,"
        + " COALESCE(SUM(CASE WHEN COALESCE(ut.enabled, true) THEN 1 ELSE 0 END), 0) AS active,"
        + " COALESCE(SUM(CASE WHEN NOT COALESCE(ut.enabled, true) THEN 1 ELSE 0 END), 0)"
        + " AS suspended"
        + " FROM "
        + USER_TABLE_NAME
        + " ut INNER JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt ON ut.metalake_id = mt.metalake_id AND mt.deleted_at = 0"
        + " WHERE mt.metalake_name = #{metalakeName} AND ut.deleted_at = 0";
  }

  private String usersForMetalakeGroupSelectAndFrom() {
    return "SELECT ut.user_id as userId, ut.user_name as userName,"
        + " ut.metalake_id as metalakeId,"
        + " ut.external_id as externalId, ut.enabled as enabled,"
        + " ut.audit_info as auditInfo,"
        + " ut.current_version as currentVersion, ut.last_version as lastVersion,"
        + " ut.deleted_at as deletedAt,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds,"
        + " MAX(CASE WHEN iu.user_name IS NOT NULL THEN 1 ELSE 0 END) as inBuiltInIdp"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt INNER JOIN "
        + GroupMetaMapper.GROUP_TABLE_NAME
        + " gt ON gt.metalake_id = mt.metalake_id AND gt.deleted_at = 0"
        + " AND gt.group_name = #{groupName}"
        + membershipUsersJoinForGroup()
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu ON iu.user_name = ut.user_name AND iu.deleted_at = 0 LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + USER_ROLE_RELATION_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rt ON rt.user_id = ut.user_id LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + ROLE_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rot ON rot.role_id = rt.role_id"
        + " WHERE mt.metalake_name = #{metalakeName} AND mt.deleted_at = 0";
  }

  private String membershipUsersJoinForGroup() {
    return " LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.group_name = gt.group_name AND ig.deleted_at = 0"
        + " AND (gt.external_id IS NULL OR gt.external_id = '')"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr ON iugr.group_id = ig.group_id AND iugr.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " ium ON ium.user_id = iugr.user_id AND ium.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur ON sur.metalake_id = mt.metalake_id AND sur.group_id = gt.group_id AND sur.deleted_at = 0"
        + " AND gt.external_id IS NOT NULL AND gt.external_id <> ''"
        + " LEFT JOIN "
        + USER_TABLE_NAME
        + " ut ON ut.metalake_id = mt.metalake_id AND ut.deleted_at = 0"
        + " AND (((gt.external_id IS NULL OR gt.external_id = '') AND ut.user_name = ium.user_name)"
        + " OR (gt.external_id IS NOT NULL AND gt.external_id <> '' AND ut.user_id = sur.user_id))";
  }

  private String usersWithOriginSelectAndFrom(boolean innerJoinUser, String extraFilter) {
    String userJoin =
        innerJoinUser
            ? " mt INNER JOIN "
                + USER_TABLE_NAME
                + " ut ON ut.metalake_id = mt.metalake_id AND ut.deleted_at = 0"
            : " mt LEFT JOIN "
                + USER_TABLE_NAME
                + " ut ON ut.metalake_id = mt.metalake_id AND ut.deleted_at = 0";
    return "SELECT ut.user_id as userId, ut.user_name as userName,"
        + " ut.metalake_id as metalakeId,"
        + " ut.external_id as externalId, ut.enabled as enabled,"
        + " ut.audit_info as auditInfo,"
        + " ut.current_version as currentVersion, ut.last_version as lastVersion,"
        + " ut.deleted_at as deletedAt,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds,"
        + " MAX(CASE WHEN iu.user_name IS NOT NULL THEN 1 ELSE 0 END) as inBuiltInIdp"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + userJoin
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu ON iu.user_name = ut.user_name AND iu.deleted_at = 0 LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + USER_ROLE_RELATION_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rt ON rt.user_id = ut.user_id LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + ROLE_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rot ON rot.role_id = rt.role_id"
        + " WHERE mt.metalake_name = #{metalakeName} AND mt.deleted_at = 0"
        + (extraFilter == null ? "" : extraFilter);
  }

  protected String metalakeIdByNameSubquery() {
    return "(SELECT metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " WHERE metalake_name = #{metalakeName} AND deleted_at = 0)";
  }

  protected String userNameInClause() {
    return "<foreach collection='userNames' item='userName' open='(' separator=',' close=')'>"
        + "#{userName}"
        + "</foreach>";
  }

  protected String jsonArrayAgg(String expr) {
    return "JSON_ARRAYAGG(" + expr + ")";
  }

  private String roleAggregationSubquery() {
    return "SELECT rt.user_id as userId,"
        + " JSON_ARRAYAGG(rot.role_name) as roleNames,"
        + " JSON_ARRAYAGG(rot.role_id) as roleIds"
        + " FROM "
        + USER_ROLE_RELATION_TABLE_NAME
        + " rt JOIN "
        + ROLE_TABLE_NAME
        + " rot ON rot.role_id = rt.role_id AND rot.deleted_at = 0"
        + " WHERE rt.deleted_at = 0"
        + " GROUP BY rt.user_id";
  }

  private String groupAggregationSubquery() {
    return "SELECT membership.userId as userId,"
        + " JSON_ARRAYAGG(membership.groupName) as groupNames"
        + " FROM ("
        + userGroupMembershipSubquery()
        + ") membership"
        + " GROUP BY membership.userId";
  }

  private String userGroupMembershipSubquery() {
    return localIdpGroupMembershipSelect() + " UNION ALL " + scimGroupMembershipSelect();
  }

  private String localIdpGroupMembershipSelect() {
    return "SELECT u.user_id as userId, g.group_name as groupName"
        + " FROM "
        + USER_TABLE_NAME
        + " u JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm ON u.metalake_id = mm.metalake_id AND mm.deleted_at = 0 AND mm.metalake_name ="
        + " #{metalakeName}"
        + " JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu ON iu.user_name = u.user_name AND iu.deleted_at = 0"
        + " JOIN "
        + DatastratoUserMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " ir ON ir.user_id = iu.user_id AND ir.deleted_at = 0"
        + " JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.group_id = ir.group_id AND ig.deleted_at = 0"
        + " JOIN "
        + GroupMetaMapper.GROUP_TABLE_NAME
        + " g ON g.group_name = ig.group_name AND g.metalake_id = u.metalake_id AND g.deleted_at ="
        + " 0"
        + " WHERE u.deleted_at = 0 AND (u.external_id IS NULL OR u.external_id = '')";
  }

  private String scimGroupMembershipSelect() {
    return "SELECT u.user_id as userId, g.group_name as groupName"
        + " FROM "
        + USER_TABLE_NAME
        + " u JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm ON u.metalake_id = mm.metalake_id AND mm.deleted_at = 0 AND mm.metalake_name ="
        + " #{metalakeName}"
        + " JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sr ON sr.user_id = u.user_id AND sr.metalake_id = u.metalake_id AND sr.deleted_at = 0"
        + " JOIN "
        + GroupMetaMapper.GROUP_TABLE_NAME
        + " g ON g.group_id = sr.group_id AND g.metalake_id = u.metalake_id AND g.deleted_at = 0"
        + " WHERE u.deleted_at = 0 AND u.external_id IS NOT NULL AND u.external_id != ''";
  }
}
