/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.SecurableObjectMapper.SECURABLE_OBJECT_TABLE_NAME;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for enterprise authorization securable object queries. */
public class DatastratoSecurableObjectBaseSQLProvider {

  /**
   * Lists securable objects by role ids.
   *
   * @param roleIds The role ids.
   * @return The list securable objects SQL.
   */
  public String listSecurableObjectsByRoleIds(@Param("roleIds") List<Long> roleIds) {
    return "<script>"
        + "SELECT role_id as roleId, metadata_object_id as metadataObjectId,"
        + " type as type, privilege_names as privilegeNames,"
        + " privilege_conditions as privilegeConditions, current_version as currentVersion,"
        + " last_version as lastVersion, deleted_at as deletedAt"
        + " FROM "
        + SECURABLE_OBJECT_TABLE_NAME
        + " WHERE role_id IN "
        + "<foreach collection='roleIds' item='roleId' open='(' close=')' separator=','>"
        + "#{roleId}"
        + "</foreach>"
        + " AND deleted_at = 0"
        + "</script>";
  }
}
