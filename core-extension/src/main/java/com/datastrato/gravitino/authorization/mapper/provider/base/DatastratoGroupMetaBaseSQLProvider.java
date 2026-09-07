/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.GroupMetaMapper.GROUP_ROLE_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.GroupMetaMapper.GROUP_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.RoleMetaMapper.ROLE_TABLE_NAME;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.ibatis.annotations.Param;

/** Base SQL for enterprise group_meta reads with built-in IdP origin checks. */
public class DatastratoGroupMetaBaseSQLProvider {

  private static final String SCIM_GROUP_ALIAS = "sg";

  /**
   * Lists active IdP group names with whether each is already in the metalake.
   *
   * @param metalakeName The metalake name.
   * @return JOIN SQL.
   */
  public String listGroupsWithMetalakeStatus(@Param("metalakeName") String metalakeName) {
    return "SELECT ig.group_name AS name,"
        + " CASE WHEN gt.group_name IS NOT NULL THEN 1 ELSE 0 END AS status"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.deleted_at = 0 LEFT JOIN "
        + GROUP_TABLE_NAME
        + " gt ON gt.group_name = ig.group_name AND gt.deleted_at = 0"
        + " AND gt.metalake_id = mt.metalake_id"
        + " WHERE mt.metalake_name = #{metalakeName} AND mt.deleted_at = 0"
        + " ORDER BY ig.group_name";
  }

  /**
   * Lists all active Local IdP group names from {@code idp_group_meta}.
   *
   * @return MyBatis SQL ordered by group name.
   */
  public String listIdpGroupNames() {
    return "SELECT group_name FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " WHERE deleted_at = 0 ORDER BY group_name";
  }

  /**
   * Loads a metalake group with roles, identity-store origin, and {@code userCount} in one JOIN.
   *
   * <p>Same origin / {@code userCount} rules as {@link #listGroupsByMetalakeWithOrigin}.
   *
   * @param metalakeName The metalake name.
   * @param groupName The group name.
   * @return JOIN SQL.
   */
  public String getGroupByMetalakeWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupName") String groupName) {
    return groupsWithOriginSelectAndFrom(true, " AND gt.group_name = #{groupName}")
        + " GROUP BY gt.group_id";
  }

  /**
   * Lists metalake groups with roles and identity-store origin in one query.
   *
   * <p>{@code origin} is Local when the name exists in {@code idp_group_meta}, Provisioned when it
   * exists in {@code scim_group_meta} (and not IdP), otherwise JIT.
   *
   * @param metalakeName The metalake name.
   * @return JOIN SQL.
   */
  public String listGroupsByMetalakeWithOrigin(@Param("metalakeName") String metalakeName) {
    return groupsWithOriginSelectAndFrom(false, null) + " GROUP BY gt.group_id";
  }

  /**
   * Loads metalake groups by name with roles and identity-store origin in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param groupNames Group names to load.
   * @return JOIN SQL.
   */
  public String listGroupsByMetalakeAndNamesWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("groupNames") List<String> groupNames) {
    return "<script>"
        + groupsWithOriginSelectAndFrom(true, " AND gt.group_name IN " + groupNameInClause())
        + " GROUP BY gt.group_id"
        + "</script>";
  }

  /**
   * Lists metalake groups for a user with roles and identity-store origin in one JOIN.
   *
   * @param metalakeName The metalake name.
   * @param userName The username.
   * @return JOIN SQL.
   */
  public String listGroupsForMetalakeUserWithOrigin(
      @Param("metalakeName") String metalakeName, @Param("userName") String userName) {
    return groupsForMetalakeUserSelectAndFrom() + " GROUP BY gt.group_id, ut.user_id";
  }

  /**
   * Loads metalake group totals and empty-group count.
   *
   * @param metalakeName The metalake name.
   * @return Aggregate SQL returning one row.
   */
  public String countGroupsWithEmptyByMetalake(@Param("metalakeName") String metalakeName) {
    return "SELECT COUNT(*) AS total,"
        + " COALESCE(SUM(CASE WHEN NOT ("
        + localGroupHasMemberExists()
        + " OR "
        + scimGroupHasMemberExists()
        + ") THEN 1 ELSE 0 END), 0) AS empty"
        + " FROM "
        + GROUP_TABLE_NAME
        + " gt INNER JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt ON gt.metalake_id = mt.metalake_id AND mt.deleted_at = 0"
        + " WHERE mt.metalake_name = #{metalakeName} AND gt.deleted_at = 0";
  }

  /**
   * Lists Directory Groups for Configure → Directory → Groups.
   *
   * <p>Local rows come from {@code idp_group_meta}; Provisioned from {@code scim_group_meta}
   * excluding IdP names; JIT from distinct {@code group_meta} names absent from both identity
   * stores. {@code memberCount} is the identity-store membership count (0 for JIT). Metalakes are
   * distinct {@code metalake_meta} names that contain the group name in {@code group_meta}.
   *
   * @return MyBatis SQL ordered by group name.
   */
  public String listDirectoryGroups() {
    return "SELECT identity.groupName as groupName, identity.originCode as originCode,"
        + " COALESCE(CASE WHEN identity.originCode = "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " THEN idpMembers.memberCount WHEN identity.originCode = "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " THEN scimMembers.memberCount ELSE 0 END, 0) as memberCount,"
        + " metalakes.metalakeNames as metalakeNames"
        + " FROM ("
        + directoryGroupIdentityUnion()
        + ") identity"
        + " LEFT JOIN ("
        + idpDirectoryMemberAggregation()
        + ") idpMembers ON identity.idpGroupId = idpMembers.groupId"
        + " LEFT JOIN ("
        + scimDirectoryMemberAggregation()
        + ") scimMembers ON identity.scimGroupId = scimMembers.groupId"
        + " LEFT JOIN ("
        + directoryGroupMetalakeAggregation()
        + ") metalakes ON metalakes.groupName = identity.groupName"
        + " ORDER BY identity.groupName";
  }

  /**
   * Returns group names that have an active row in {@code idp_group_meta}.
   *
   * @param groupNames Group names to check.
   * @return MyBatis script SQL.
   */
  public String selectIdpGroupNamesByNames(@Param("groupNames") List<String> groupNames) {
    return "<script>"
        + "SELECT group_name FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " WHERE deleted_at = 0 AND group_name IN "
        + groupNameInClause()
        + "</script>";
  }

  /**
   * Returns active IdP user ids for the given names.
   *
   * @param userNames Usernames to resolve.
   * @return MyBatis script SQL.
   */
  public String selectIdpUserIdsByNames(@Param("userNames") List<String> userNames) {
    return "<script>"
        + "SELECT user_name as userName, user_id as userId FROM "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " WHERE deleted_at = 0 AND user_name IN "
        + userNameInClause()
        + "</script>";
  }

  /**
   * Inserts a Local Directory Group into {@code idp_group_meta}.
   *
   * @param groupId Generated group id.
   * @param groupName Group name.
   * @param groupComment Group comment.
   * @return Insert SQL.
   */
  public String insertIdpGroup(
      @Param("groupId") long groupId,
      @Param("groupName") String groupName,
      @Param("groupComment") String groupComment) {
    return "INSERT INTO "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " (group_id, group_name, group_comment, current_version, last_version, deleted_at)"
        + " VALUES (#{groupId}, #{groupName}, COALESCE(#{groupComment}, ''), 1, 1, 0)";
  }

  /**
   * Batch-inserts {@code idp_user_group_rel} rows for a new Directory Group.
   *
   * @param relations Relation rows.
   * @return MyBatis script SQL.
   */
  public String batchInsertIdpUserGroupRels(
      @Param("relations") List<IdpUserGroupRelInsertPO> relations) {
    return "<script>"
        + "INSERT INTO "
        + DatastratoGroupMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " (id, user_id, group_id, current_version, last_version, deleted_at)"
        + " VALUES "
        + "<foreach item='item' collection='relations' separator=','>"
        + "(#{item.id}, #{item.userId}, #{item.groupId}, 1, 1, 0)"
        + "</foreach>"
        + "</script>";
  }

  private String directoryGroupIdentityUnion() {
    return "SELECT ig.group_name as groupName, "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " as originCode,"
        + " ig.group_id as idpGroupId, "
        + nullLongLiteral()
        + " as scimGroupId"
        + " FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig WHERE ig.deleted_at = 0"
        + " UNION ALL "
        + "SELECT sg.group_name as groupName, "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " as originCode,"
        + " "
        + nullLongLiteral()
        + " as idpGroupId, sg.group_id as scimGroupId"
        + " FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg WHERE sg.deleted_at = 0 AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig WHERE ig.group_name = sg.group_name AND ig.deleted_at = 0)"
        + " UNION ALL "
        + "SELECT gt.group_name as groupName, "
        + IdentitySource.ORIGIN_CODE_JIT
        + " as originCode,"
        + " "
        + nullLongLiteral()
        + " as idpGroupId, "
        + nullLongLiteral()
        + " as scimGroupId"
        + " FROM "
        + GROUP_TABLE_NAME
        + " gt WHERE gt.deleted_at = 0 AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig WHERE ig.group_name = gt.group_name AND ig.deleted_at = 0)"
        + " AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg WHERE sg.group_name = gt.group_name AND sg.deleted_at = 0)"
        + " GROUP BY gt.group_name";
  }

  private String idpDirectoryMemberAggregation() {
    return "SELECT iugr.group_id as groupId, COUNT(DISTINCT iugr.user_id) as memberCount FROM "
        + DatastratoGroupMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr WHERE iugr.deleted_at = 0 GROUP BY iugr.group_id";
  }

  private String scimDirectoryMemberAggregation() {
    return "SELECT sur.group_id as groupId, COUNT(DISTINCT sur.user_id) as memberCount FROM "
        + DatastratoGroupMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur WHERE sur.deleted_at = 0 GROUP BY sur.group_id";
  }

  private String directoryGroupMetalakeAggregation() {
    return "SELECT gt.group_name as groupName, "
        + jsonArrayAgg("mt.metalake_name")
        + " as metalakeNames FROM "
        + GROUP_TABLE_NAME
        + " gt JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt ON mt.metalake_id = gt.metalake_id AND mt.deleted_at = 0"
        + " WHERE gt.deleted_at = 0 GROUP BY gt.group_name";
  }

  private String localGroupHasMemberExists() {
    return "EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig INNER JOIN "
        + DatastratoGroupMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr ON iugr.group_id = ig.group_id AND iugr.deleted_at = 0"
        + " INNER JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " ium ON ium.user_id = iugr.user_id AND ium.deleted_at = 0"
        + " INNER JOIN "
        + UserMetaMapper.USER_TABLE_NAME
        + " ut ON ut.metalake_id = gt.metalake_id AND ut.user_name = ium.user_name"
        + " AND ut.deleted_at = 0"
        + " WHERE ig.group_name = gt.group_name AND ig.deleted_at = 0"
        + " AND NOT EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg WHERE sg.group_name = gt.group_name AND sg.deleted_at = 0))";
  }

  private String scimGroupHasMemberExists() {
    return "EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg INNER JOIN "
        + DatastratoGroupMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur ON sur.group_id = sg.group_id AND sur.deleted_at = 0"
        + " INNER JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su ON su.user_id = sur.user_id AND su.deleted_at = 0"
        + " INNER JOIN "
        + UserMetaMapper.USER_TABLE_NAME
        + " ut ON ut.metalake_id = gt.metalake_id AND ut.user_name = su.user_name AND ut.deleted_at = 0"
        + " WHERE sg.group_name = gt.group_name AND sg.deleted_at = 0)";
  }

  private String groupUserCountSelect() {
    return " COALESCE(CASE WHEN EXISTS (SELECT 1 FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg WHERE sg.group_name = gt.group_name AND sg.deleted_at = 0) THEN ("
        + scimGroupUserCountSubquery()
        + ") ELSE ("
        + localGroupUserCountSubquery()
        + ") END, 0) as userCount";
  }

  private String localGroupUserCountSubquery() {
    return "SELECT COUNT(DISTINCT ut.user_id) FROM "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig INNER JOIN "
        + DatastratoGroupMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr ON iugr.group_id = ig.group_id AND iugr.deleted_at = 0"
        + " INNER JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " ium ON ium.user_id = iugr.user_id AND ium.deleted_at = 0"
        + " INNER JOIN "
        + UserMetaMapper.USER_TABLE_NAME
        + " ut ON ut.metalake_id = gt.metalake_id AND ut.user_name = ium.user_name"
        + " AND ut.deleted_at = 0"
        + " WHERE ig.group_name = gt.group_name AND ig.deleted_at = 0";
  }

  private String scimGroupUserCountSubquery() {
    return "SELECT COUNT(DISTINCT ut.user_id) FROM "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sg INNER JOIN "
        + DatastratoGroupMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur ON sur.group_id = sg.group_id AND sur.deleted_at = 0"
        + " INNER JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su ON su.user_id = sur.user_id AND su.deleted_at = 0"
        + " INNER JOIN "
        + UserMetaMapper.USER_TABLE_NAME
        + " ut ON ut.metalake_id = gt.metalake_id AND ut.user_name = su.user_name AND ut.deleted_at = 0"
        + " WHERE sg.group_name = gt.group_name AND sg.deleted_at = 0";
  }

  private String groupsForMetalakeUserSelectAndFrom() {
    return "SELECT gt.group_id as groupId, gt.group_name as groupName,"
        + " gt.metalake_id as metalakeId,"
        + " gt.audit_info as auditInfo,"
        + " gt.current_version as currentVersion, gt.last_version as lastVersion,"
        + " gt.deleted_at as deletedAt,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds,"
        + " CASE WHEN MAX(CASE WHEN ig.group_name IS NOT NULL THEN 1 ELSE 0 END) = 1 THEN "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " WHEN MAX(CASE WHEN sgm.group_name IS NOT NULL THEN 1 ELSE 0 END) = 1 THEN "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " ELSE "
        + IdentitySource.ORIGIN_CODE_JIT
        + " END as originCode"
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " mt INNER JOIN "
        + UserMetaMapper.USER_TABLE_NAME
        + " ut ON ut.metalake_id = mt.metalake_id AND ut.deleted_at = 0"
        + " AND ut.user_name = #{userName}"
        + membershipGroupsJoinForUser()
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.group_name = gt.group_name AND ig.deleted_at = 0 LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + GROUP_ROLE_RELATION_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rt ON rt.group_id = gt.group_id LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + ROLE_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rot ON rot.role_id = rt.role_id"
        + " WHERE mt.metalake_name = #{metalakeName} AND mt.deleted_at = 0";
  }

  private String membershipGroupsJoinForUser() {
    return " LEFT JOIN "
        + DatastratoUserMetaMapper.SCIM_USER_TABLE_NAME
        + " su ON su.user_name = ut.user_name AND su.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoUserMetaMapper.IDP_USER_TABLE_NAME
        + " iu ON iu.user_name = ut.user_name AND iu.deleted_at = 0 AND su.user_id IS NULL"
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_USER_GROUP_REL_TABLE_NAME
        + " iugr ON iugr.user_id = iu.user_id AND iugr.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " igm ON igm.group_id = iugr.group_id AND igm.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " sur ON sur.user_id = su.user_id AND sur.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " sgm ON sgm.group_id = sur.group_id AND sgm.deleted_at = 0"
        + " LEFT JOIN "
        + GROUP_TABLE_NAME
        + " gt ON gt.metalake_id = mt.metalake_id AND gt.deleted_at = 0"
        + " AND ((su.user_id IS NULL AND gt.group_name = igm.group_name)"
        + " OR (su.user_id IS NOT NULL AND gt.group_name = sgm.group_name))";
  }

  private String groupsWithOriginSelectAndFrom(boolean innerJoinGroup, String extraFilter) {
    String groupJoin =
        innerJoinGroup
            ? " mt INNER JOIN "
                + GROUP_TABLE_NAME
                + " gt ON gt.metalake_id = mt.metalake_id AND gt.deleted_at = 0"
            : " mt LEFT JOIN "
                + GROUP_TABLE_NAME
                + " gt ON gt.metalake_id = mt.metalake_id AND gt.deleted_at = 0";
    return "SELECT gt.group_id as groupId, gt.group_name as groupName,"
        + " gt.metalake_id as metalakeId,"
        + " gt.audit_info as auditInfo,"
        + " gt.current_version as currentVersion, gt.last_version as lastVersion,"
        + " gt.deleted_at as deletedAt,"
        + " "
        + jsonArrayAgg("rot.role_name")
        + " as roleNames,"
        + " "
        + jsonArrayAgg("rot.role_id")
        + " as roleIds,"
        + " CASE WHEN MAX(CASE WHEN ig.group_name IS NOT NULL THEN 1 ELSE 0 END) = 1 THEN "
        + IdentitySource.ORIGIN_CODE_LOCAL
        + " WHEN MAX(CASE WHEN "
        + SCIM_GROUP_ALIAS
        + ".group_name IS NOT NULL THEN 1 ELSE 0 END) = 1 THEN "
        + IdentitySource.ORIGIN_CODE_PROVISIONED
        + " ELSE "
        + IdentitySource.ORIGIN_CODE_JIT
        + " END as originCode,"
        + groupUserCountSelect()
        + " FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + groupJoin
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.IDP_GROUP_TABLE_NAME
        + " ig ON ig.group_name = gt.group_name AND ig.deleted_at = 0"
        + " LEFT JOIN "
        + DatastratoGroupMetaMapper.SCIM_GROUP_TABLE_NAME
        + " "
        + SCIM_GROUP_ALIAS
        + " ON "
        + SCIM_GROUP_ALIAS
        + ".group_name = gt.group_name AND "
        + SCIM_GROUP_ALIAS
        + ".deleted_at = 0 LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + GROUP_ROLE_RELATION_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rt ON rt.group_id = gt.group_id LEFT OUTER JOIN ("
        + " SELECT * FROM "
        + ROLE_TABLE_NAME
        + " WHERE deleted_at = 0)"
        + " AS rot ON rot.role_id = rt.role_id"
        + " WHERE mt.metalake_name = #{metalakeName} AND mt.deleted_at = 0"
        + (extraFilter == null ? "" : extraFilter);
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

  protected String groupNameInClause() {
    return "<foreach collection='groupNames' item='groupName' open='(' separator=',' close=')'>"
        + "#{groupName}"
        + "</foreach>";
  }

  protected String userNameInClause() {
    return "<foreach collection='userNames' item='userName' open='(' separator=',' close=')'>"
        + "#{userName}"
        + "</foreach>";
  }
}
