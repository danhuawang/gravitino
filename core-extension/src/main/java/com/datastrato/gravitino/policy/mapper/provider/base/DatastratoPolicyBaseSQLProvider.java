/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.policy.mapper.provider.base;

import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetadataObjectRelMapper;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for enterprise policy relational queries. */
public class DatastratoPolicyBaseSQLProvider {

  /**
   * Generates SQL to list associated metadata objects for selected policies under a metalake.
   *
   * @param metalakeName The metalake name.
   * @param policyIds The policy IDs to query.
   * @return The SQL string.
   */
  public String listAssociatedMetadataObjectsForPolicies(
      @Param("metalakeName") String metalakeName, @Param("policyIds") List<Long> policyIds) {
    return "<script>"
        + "SELECT pe.policy_id as policyId, pe.metadata_object_id as metadataObjectId,"
        + " pe.metadata_object_type as metadataObjectType"
        + " FROM "
        + PolicyMetadataObjectRelMapper.POLICY_METADATA_OBJECT_RELATION_TABLE_NAME
        + " pe JOIN "
        + PolicyMetaMapper.POLICY_META_TABLE_NAME
        + " pm ON pe.policy_id = pm.policy_id"
        + " JOIN "
        + MetalakeMetaMapper.TABLE_NAME
        + " mm ON pm.metalake_id = mm.metalake_id"
        + " WHERE mm.metalake_name = #{metalakeName}"
        + " AND pe.policy_id IN "
        + "<foreach item='policyId' collection='policyIds' open='(' separator=',' close=')'>"
        + "#{policyId}"
        + "</foreach>"
        + " AND pe.deleted_at = 0 AND pm.deleted_at = 0 AND mm.deleted_at = 0"
        + "</script>";
  }
}
