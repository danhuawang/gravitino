/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.UserMetaMapper.USER_TABLE_NAME;

import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.ibatis.annotations.Param;

/**
 * Base SQL for enterprise batch user_meta reads and updates.
 *
 * <p>MySQL, H2, and PostgreSQL share the same statements (no JOIN-form {@code UPDATE}). Metalake
 * scope is a scalar subquery on {@code metalake_name} so callers do not resolve {@code metalake_id}
 * first.
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
   * Builds a batch UPDATE for users that already passed validation (exist and have null {@code
   * external_id}). {@code external_id IS NULL} remains as a defensive filter.
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
   * @return Scalar subquery that resolves {@code metalake_id} from {@code metalake_name}.
   */
  protected String metalakeIdByNameSubquery() {
    return "(SELECT metalake_id FROM "
        + MetalakeMetaMapper.TABLE_NAME
        + " WHERE metalake_name = #{metalakeName} AND deleted_at = 0)";
  }

  /**
   * @return MyBatis foreach IN clause for {@code userNames}.
   */
  protected String userNameInClause() {
    return "<foreach collection='userNames' item='userName' open='(' separator=',' close=')'>"
        + "#{userName}"
        + "</foreach>";
  }
}
