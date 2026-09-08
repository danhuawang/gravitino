/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.RoleMetaMapper.ROLE_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_TABLE_NAME;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
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

  private static final String SCIM_USER_ALIAS = "su";

  private static final String IDP_USER_ALIAS = "iu";

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
        + "SELECT ut.user_id as userId, ut.user_name as userName,"
        + " ut.metalake_id as metalakeId,"
        + " ut.audit_info as auditInfo, ut.current_version as currentVersion,"
        + " ut.last_version as lastVersion, ut.deleted_at as deletedAt"
        + " FROM "
        + USER_TABLE_NAME
        + " ut"
        + " WHERE ut.deleted_at = 0"
        + " AND ut.metalake_id = "
        + metalakeIdByNameSubquery()
        + " AND ut.user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Builds a batch UPDATE of {@code idp_user_meta.enabled} for local metalake users.
   *
   * <p>{@code user_meta} no longer stores {@code enabled}; local login state lives on the built-in
   * IdP. Rows present in {@code scim_user_meta} are excluded (those use {@link
   * #batchUpdateScimUserEnabledByUserNames}).
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
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " "
        + IDP_USER_ALIAS
        + " SET enabled = #{enabled},"
        + " last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE "
        + IDP_USER_ALIAS
        + ".deleted_at = 0"
        + " AND "
        + IDP_USER_ALIAS
        + ".user_name IN "
        + userNameInClause()
        + " AND EXISTS (SELECT 1 FROM "
        + USER_TABLE_NAME
        + " ut WHERE ut.user_name = "
        + IDP_USER_ALIAS
        + ".user_name AND ut.deleted_at = 0 AND ut.metalake_id = "
        + metalakeIdByNameSubquery()
        + ")"
        + " AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " "
        + SCIM_USER_ALIAS
        + " WHERE "
        + SCIM_USER_ALIAS
        + ".user_name = "
        + IDP_USER_ALIAS
        + ".user_name AND "
        + SCIM_USER_ALIAS
        + ".deleted_at = 0)"
        + "</script>";
  }

  /**
   * Returns usernames that have an active row in {@code scim_user_meta}.
   *
   * @param userNames Usernames to check.
   * @return MyBatis script SQL.
   */
  public String selectScimUserNamesByNames(@Param("userNames") List<String> userNames) {
    return "<script>"
        + "SELECT user_name FROM "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " WHERE deleted_at = 0 AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Batch-updates {@code enabled} for provisioned users in {@code scim_user_meta}.
   *
   * @param userNames Distinct usernames.
   * @param enabled Target enabled value.
   * @return MyBatis script SQL.
   */
  public String batchUpdateScimUserEnabledByUserNames(
      @Param("userNames") List<String> userNames, @Param("enabled") boolean enabled) {
    return "<script>"
        + "UPDATE "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " SET enabled = #{enabled},"
        + " last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE deleted_at = 0 AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Returns usernames that have an active row in {@code idp_user_meta}.
   *
   * @param userNames Usernames to check.
   * @return MyBatis script SQL.
   */
  public String selectIdpUserNamesByNames(@Param("userNames") List<String> userNames) {
    return "<script>"
        + "SELECT user_name FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " WHERE deleted_at = 0 AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Lists all active Local IdP usernames from {@code idp_user_meta}.
   *
   * @return MyBatis SQL ordered by username.
   */
  public String listIdpUserNames() {
    return "SELECT user_name FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " WHERE deleted_at = 0 ORDER BY user_name";
  }

  /**
   * Batch-updates {@code enabled} for Local Directory Users in {@code idp_user_meta}.
   *
   * @param userNames Distinct usernames already validated as Local IdP users.
   * @param enabled Target enabled value.
   * @return MyBatis script SQL.
   */
  public String batchUpdateIdpUserEnabledByUserNames(
      @Param("userNames") List<String> userNames, @Param("enabled") boolean enabled) {
    return "<script>"
        + "UPDATE "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " SET enabled = #{enabled},"
        + " last_version = current_version,"
        + " current_version = current_version + 1"
        + " WHERE deleted_at = 0 AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Returns active IdP group ids for the given names.
   *
   * @param groupNames Group names to resolve.
   * @return MyBatis script SQL.
   */
  public String selectIdpGroupIdsByNames(@Param("groupNames") List<String> groupNames) {
    return "<script>"
        + "SELECT group_name as groupName, group_id as groupId FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " WHERE deleted_at = 0 AND group_name IN "
        + groupNameInClause()
        + "</script>";
  }

  /**
   * Inserts a Local Directory User into {@code idp_user_meta}.
   *
   * @param userId Generated user id.
   * @param userName Username.
   * @param passwordHash Hashed password.
   * @param enabled Whether the user is enabled.
   * @return Insert SQL.
   */
  public String insertIdpUser(
      @Param("userId") long userId,
      @Param("userName") String userName,
      @Param("passwordHash") String passwordHash,
      @Param("enabled") boolean enabled) {
    return "INSERT INTO "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " (user_id, user_name, password_hash, enabled, current_version, last_version, deleted_at)"
        + " VALUES (#{userId}, #{userName}, #{passwordHash}, #{enabled}, 1, 1, 0)";
  }

  /**
   * Batch-inserts {@code idp_user_group_rel} rows for a new Directory User.
   *
   * @param relations Relation rows.
   * @return MyBatis script SQL.
   */
  public String batchInsertIdpUserGroupRels(
      @Param("relations") List<IdpUserGroupRelInsertPO> relations) {
    return "<script>"
        + "INSERT INTO "
        + DatastratoUserMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " (id, user_id, group_id, current_version, last_version, deleted_at)"
        + " VALUES "
        + "<foreach item='item' collection='relations' separator=','>"
        + "(#{item.id}, #{item.userId}, #{item.groupId}, 1, 1, 0)"
        + "</foreach>"
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
   * Loads a metalake user with roles and identity-store origin in one JOIN.
   *
   * <p>Same origin rules as {@link #listUserWithGroupsPOsByMetalakeName}.
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
   * Lists metalake users with roles and identity-store origin in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @return JOIN SQL.
   */
  public String listUsersByMetalakeWithOrigin(@Param("metalakeName") String metalakeName) {
    return usersWithOriginSelectAndFrom(false, null) + " GROUP BY ut.user_id";
  }

  /**
   * Loads metalake users by name with roles and identity-store origin in one JOIN.
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
   * Lists metalake users in a group with roles and identity-store origin in one JOIN.
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
   * Lists metalake users with roles, group names, and identity-store origin in one query.
   *
   * <p>{@code origin} is Local when the name exists in {@code idp_user_meta}, Provisioned when it
   * exists in {@code scim_user_meta} (and not IdP), otherwise JIT.
   *
   * @param metalakeName The metalake name.
   * @return MyBatis SQL.
   */
  public String listUserWithGroupsPOsByMetalakeName(@Param("metalakeName") String metalakeName) {
    return "SELECT ut.user_id as userId, ut.user_name as userName,"
        + " ut.metalake_id as metalakeId,"
        + " ut.audit_info as auditInfo,"
        + " ut.current_version as currentVersion, ut.last_version as lastVersion,"
        + " ut.deleted_at as deletedAt,"
        + " roles.roleNames as roleNames,"
        + " roles.roleIds as roleIds,"
        + " userGroups.groupNames as groupNames,"
        + " CASE WHEN iu.user_name IS NOT NULL THEN "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " WHEN "
        + SCIM_USER_ALIAS
        + ".user_name IS NOT NULL THEN "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " ELSE "
        + IdentitySource.ORIGIN_CODE_JIT
        + " END as originCode"
        + " FROM "
        + USER_TABLE_NAME
        + " ut"
        + scimUserJoin("ut")
        + " JOIN "
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
   * Lists Directory Users for Configure → Directory → Users.
   *
   * <p>Local rows come from {@code idp_user_meta}; Provisioned from {@code scim_user_meta}
   * excluding IdP names; JIT from distinct {@code user_meta} names absent from both identity
   * stores. Groups are identity-store group names (empty for JIT). Metalakes are distinct {@code
   * metalake_meta} names that contain the username in {@code user_meta}.
   *
   * @return MyBatis SQL ordered by username.
   */
  public String listDirectoryUsers() {
    return "SELECT identity.userName as userName, identity.enabled as enabled,"
        + " identity.originCode as originCode,"
        + " CASE WHEN identity.originCode = "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " THEN idpGroups.groupNames WHEN identity.originCode = "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " THEN scimGroups.groupNames ELSE NULL END as groupNames,"
        + " metalakes.metalakeNames as metalakeNames"
        + " FROM ("
        + directoryIdentityUnion()
        + ") identity"
        + " LEFT JOIN ("
        + idpDirectoryGroupAggregation()
        + ") idpGroups ON identity.idpUserId = idpGroups.userId"
        + " LEFT JOIN ("
        + scimDirectoryGroupAggregation()
        + ") scimGroups ON identity.scimUserId = scimGroups.userId"
        + " LEFT JOIN ("
        + directoryMetalakeAggregation()
        + ") metalakes ON metalakes.userName = identity.userName"
        + " ORDER BY identity.userName";
  }

  /**
   * Builds the Local / Provisioned / JIT identity UNION for Directory Users.
   *
   * @return UNION ALL SQL selecting {@code userName}, {@code enabled}, {@code originCode}, and IdP
   *     / SCIM ids.
   */
  protected String directoryIdentityUnion() {
    return "SELECT iu.user_name as userName, iu.enabled as enabled, "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " as originCode,"
        + " iu.user_id as idpUserId, "
        + nullLongLiteral()
        + " as scimUserId"
        + " FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu WHERE iu.deleted_at = 0"
        + " UNION ALL "
        + "SELECT su.user_name as userName, "
        + scimUserEnabledAsBoolean()
        + " as enabled, "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " as originCode,"
        + " "
        + nullLongLiteral()
        + " as idpUserId, su.user_id as scimUserId"
        + " FROM "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su WHERE su.deleted_at = 0 AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu WHERE iu.user_name = su.user_name AND iu.deleted_at = 0)"
        + " UNION ALL "
        + "SELECT ut.user_name as userName,"
        + " TRUE as enabled, "
        + IdentitySource.ORIGIN_CODE_JIT
        + " as originCode,"
        + " "
        + nullLongLiteral()
        + " as idpUserId, "
        + nullLongLiteral()
        + " as scimUserId"
        + " FROM "
        + USER_TABLE_NAME
        + " ut WHERE ut.deleted_at = 0 AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu WHERE iu.user_name = ut.user_name AND iu.deleted_at = 0)"
        + " AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su WHERE su.user_name = ut.user_name AND su.deleted_at = 0)"
        + " GROUP BY ut.user_name";
  }

  /**
   * Expression that projects {@code scim_user_meta.enabled} as a boolean for UNION with {@code
   * idp_user_meta.enabled} / {@code TRUE}.
   *
   * <p>MySQL / H2 store SCIM {@code enabled} as {@code TINYINT(1)} which aligns with boolean
   * comparisons; PostgreSQL uses {@code SMALLINT} and needs an explicit cast (see factory
   * override).
   *
   * @return SQL boolean expression over alias {@code su}.
   */
  protected String scimUserEnabledAsBoolean() {
    return "su.enabled";
  }

  private String idpDirectoryGroupAggregation() {
    return "SELECT iugr.user_id as userId, "
        + jsonArrayAgg("ig.group_name")
        + " as groupNames FROM "
        + DatastratoUserMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.group_id = iugr.group_id AND ig.deleted_at = 0"
        + " WHERE iugr.deleted_at = 0 GROUP BY iugr.user_id";
  }

  private String scimDirectoryGroupAggregation() {
    return "SELECT sur.user_id as userId, "
        + jsonArrayAgg("sg.group_name")
        + " as groupNames FROM "
        + DatastratoUserMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur JOIN "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg ON sg.group_id = sur.group_id AND sg.deleted_at = 0"
        + " WHERE sur.deleted_at = 0 GROUP BY sur.user_id";
  }

  private String directoryMetalakeAggregation() {
    return "SELECT ut.user_name as userName, "
        + jsonArrayAgg("mt.metalake_name")
        + " as metalakeNames FROM "
        + USER_TABLE_NAME
        + " ut JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt ON mt.metalake_id = ut.metalake_id AND mt.deleted_at = 0"
        + " WHERE ut.deleted_at = 0 GROUP BY ut.user_name";
  }

  /**
   * Loads metalake user totals split by identity-store {@code enabled}.
   *
   * <p>Uses {@code scim_user_meta.enabled} for provisioned users and {@code idp_user_meta.enabled}
   * for local users; never reads {@code user_meta}.
   *
   * @param metalakeName The metalake name.
   * @return Aggregate SQL returning one row.
   */
  public String countUsersByEnabledByMetalake(@Param("metalakeName") String metalakeName) {
    return "SELECT COUNT(*) AS total,"
        + " COALESCE(SUM(CASE WHEN "
        + coalescedEnabledWithDefault()
        + " THEN 1 ELSE 0 END), 0) AS active,"
        + " COALESCE(SUM(CASE WHEN NOT "
        + coalescedEnabledWithDefault()
        + " THEN 1 ELSE 0 END), 0)"
        + " AS suspended"
        + " FROM "
        + USER_TABLE_NAME
        + " ut"
        + scimUserJoin("ut")
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " "
        + IDP_USER_ALIAS
        + " ON "
        + IDP_USER_ALIAS
        + ".user_name = ut.user_name AND "
        + IDP_USER_ALIAS
        + ".deleted_at = 0"
        + " INNER JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt ON ut.metalake_id = mt.metalake_id AND mt.deleted_at = 0"
        + " WHERE mt.metalake_name = #{metalakeName} AND ut.deleted_at = 0";
  }

  private String usersForMetalakeGroupSelectAndFrom() {
    return "SELECT ut.user_id as userId, ut.user_name as userName,"
        + " ut.metalake_id as metalakeId,"
        + " ut.audit_info as auditInfo,"
        + " ut.current_version as currentVersion, ut.last_version as lastVersion,"
        + " ut.deleted_at as deletedAt,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds,"
        + " "
        + originCodeSelect()
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt INNER JOIN "
        + GroupMetaMapper.GROUP_TABLE_NAME
        + " gt ON gt.metalake_id = mt.metalake_id AND gt.deleted_at = 0"
        + " AND gt.group_name = #{groupName}"
        + membershipUsersJoinForGroup()
        + scimUserJoin("ut")
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
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg ON sg.group_name = gt.group_name AND sg.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.group_name = gt.group_name AND ig.deleted_at = 0 AND sg.group_id IS NULL"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr ON iugr.group_id = ig.group_id AND iugr.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " ium ON ium.user_id = iugr.user_id AND ium.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur ON sur.group_id = sg.group_id AND sur.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " sum ON sum.user_id = sur.user_id AND sum.deleted_at = 0"
        + " LEFT JOIN "
        + USER_TABLE_NAME
        + " ut ON ut.metalake_id = mt.metalake_id AND ut.deleted_at = 0"
        + " AND ((sg.group_id IS NULL AND ut.user_name = ium.user_name)"
        + " OR (sg.group_id IS NOT NULL AND ut.user_name = sum.user_name))";
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
        + " ut.audit_info as auditInfo,"
        + " ut.current_version as currentVersion, ut.last_version as lastVersion,"
        + " ut.deleted_at as deletedAt,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds,"
        + " "
        + originCodeSelect()
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + userJoin
        + scimUserJoin("ut")
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

  /** Local / Provisioned / JIT origin code; uses MAX for GROUP BY role joins. */
  private String originCodeSelect() {
    return "CASE WHEN MAX(CASE WHEN iu.user_name IS NOT NULL THEN 1 ELSE 0 END) = 1 THEN "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " WHEN MAX(CASE WHEN "
        + SCIM_USER_ALIAS
        + ".user_name IS NOT NULL THEN 1 ELSE 0 END) = 1 THEN "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " ELSE "
        + IdentitySource.ORIGIN_CODE_JIT
        + " END as originCode";
  }

  protected String scimUserJoin(String userTableAlias) {
    return " LEFT JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " "
        + SCIM_USER_ALIAS
        + " ON "
        + SCIM_USER_ALIAS
        + ".user_name = "
        + userTableAlias
        + ".user_name AND "
        + SCIM_USER_ALIAS
        + ".deleted_at = 0";
  }

  /**
   * Effective enabled flag from identity tables only ({@code scim_user_meta}, then {@code
   * idp_user_meta}).
   */
  protected String coalescedEnabled() {
    return "COALESCE(" + SCIM_USER_ALIAS + ".enabled, " + IDP_USER_ALIAS + ".enabled)";
  }

  protected String coalescedEnabledWithDefault() {
    return "COALESCE(" + coalescedEnabled() + ", true)";
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

  protected String groupNameInClause() {
    return "<foreach collection='groupNames' item='groupName' open='(' separator=',' close=')'>"
        + "#{groupName}"
        + "</foreach>";
  }

  protected String jsonArrayAgg(String expr) {
    return "JSON_ARRAYAGG(" + expr + ")";
  }

  /**
   * Typed NULL used to align UNION ALL id columns.
   *
   * <p>PostgreSQL and H2 accept {@code CAST(NULL AS BIGINT)}. MySQL does not; subclasses override
   * this to {@code CAST(NULL AS SIGNED)}.
   *
   * @return SQL NULL literal with an integer type.
   */
  protected String nullLongLiteral() {
    return "CAST(NULL AS BIGINT)";
  }

  private String roleAggregationSubquery() {
    return "SELECT rt.user_id as userId,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds"
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
        + " "
        + jsonArrayAgg("membership.groupName")
        + " as groupNames"
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
        + " WHERE u.deleted_at = 0 AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su WHERE su.user_name = u.user_name AND su.deleted_at = 0)";
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
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su ON su.user_name = u.user_name AND su.deleted_at = 0"
        + " JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sr ON sr.user_id = su.user_id AND sr.deleted_at = 0"
        + " JOIN "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg ON sg.group_id = sr.group_id AND sg.deleted_at = 0"
        + " JOIN "
        + GroupMetaMapper.GROUP_TABLE_NAME
        + " g ON g.group_name = sg.group_name AND g.metalake_id = u.metalake_id AND g.deleted_at = 0"
        + " WHERE u.deleted_at = 0";
  }
}
