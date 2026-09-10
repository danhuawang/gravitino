/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.storage.mapper.JitIdentityMapper;
import com.datastrato.gravitino.scim.storage.mapper.provider.base.JitIdentityBaseSQLProvider;

/** PostgreSQL SQL provider for global JWT-discovered identity metadata. */
public class JitIdentityPostgreSQLProvider extends JitIdentityBaseSQLProvider {

  @Override
  public String deleteMembershipsByLegacyTimeline(Long legacyTimeline, int limit) {
    return deleteByLegacyTimeline(JitIdentityMapper.JIT_USER_GROUP_REL_TABLE_NAME, "id");
  }

  @Override
  public String deleteUsersByLegacyTimeline(Long legacyTimeline, int limit) {
    return deleteByLegacyTimeline(JitIdentityMapper.JIT_USER_TABLE_NAME, "user_id");
  }

  @Override
  public String deleteGroupsByLegacyTimeline(Long legacyTimeline, int limit) {
    return deleteByLegacyTimeline(JitIdentityMapper.JIT_GROUP_TABLE_NAME, "group_id");
  }

  private static String deleteByLegacyTimeline(String table, String idColumn) {
    return "DELETE FROM "
        + table
        + " WHERE "
        + idColumn
        + " IN (SELECT "
        + idColumn
        + " FROM "
        + table
        + " WHERE deleted_at > 0 AND deleted_at < #{legacyTimeline} LIMIT #{limit})";
  }
}
