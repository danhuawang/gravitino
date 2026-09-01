/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.mapper.provider.base.ScimUserGroupRelBaseSQLProvider;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** SQL provider for SCIM v2 user-group membership statements on PostgreSQL backends. */
public class ScimUserGroupRelPostgreSQLProvider extends ScimUserGroupRelBaseSQLProvider {

  @Override
  public String insertMemberships(
      @Param("groupId") long groupId,
      @Param("userIds") List<Long> userIds,
      @Param("currentVersion") Long currentVersion,
      @Param("lastVersion") Long lastVersion) {
    return "<script><if test='userIds != null and userIds.size() > 0'>INSERT INTO "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " (user_id, group_id, current_version, last_version, deleted_at)"
        + " SELECT u.user_id, #{groupId}, #{currentVersion}, #{lastVersion}, 0 FROM "
        + ScimUserMetaMapper.TABLE_NAME
        + " u WHERE u.deleted_at = 0 AND u.user_id IN ("
        + "<foreach collection='userIds' item='userId' separator=','>#{userId}</foreach>)"
        + " ON CONFLICT (user_id, group_id, deleted_at) DO UPDATE SET"
        + " user_id = EXCLUDED.user_id, group_id = EXCLUDED.group_id,"
        + " current_version = EXCLUDED.current_version, last_version = EXCLUDED.last_version,"
        + " deleted_at = EXCLUDED.deleted_at</if></script>";
  }

  @Override
  public String deleteByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return "DELETE FROM "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " WHERE id IN (SELECT id FROM "
        + ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit})";
  }

  @Override
  protected String softDeleteTimestampExpression() {
    return "CAST(FLOOR(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000) AS BIGINT)";
  }
}
