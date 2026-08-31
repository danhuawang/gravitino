/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.tag.mapper.provider.base;

import static org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper.POLICY_META_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.PolicyMetadataObjectRelMapper.POLICY_METADATA_OBJECT_RELATION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.PolicyVersionMapper.POLICY_VERSION_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.TagMetaMapper.TAG_TABLE_NAME;
import static org.apache.gravitino.storage.relational.mapper.TagMetadataObjectRelMapper.TAG_METADATA_OBJECT_RELATION_TABLE_NAME;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Base SQL provider for enterprise batch tag and policy metadata object queries. */
public class DatastratoTagPolicyMetadataObjectBaseSQLProvider {

  /**
   * Builds SQL for batch querying tag relations by object ids across all object types.
   *
   * @param metadataObjectIds The metadata object ids.
   * @return The SQL query.
   */
  public String batchListTagRelPOsByMetadataObjectIds(
      @Param("metadataObjectIds") List<Long> metadataObjectIds) {
    return "<script>"
        + "SELECT te.metadata_object_id as metadataObjectId, te.metadata_object_type as metadataObjectType,"
        + " tm.tag_id as tagId, tm.tag_name as tagName, tm.metalake_id as metalakeId,"
        + " tm.tag_comment as comment, tm.properties as properties, tm.audit_info as auditInfo,"
        + " tm.current_version as currentVersion, tm.last_version as lastVersion, tm.deleted_at as deletedAt"
        + " FROM "
        + TAG_TABLE_NAME
        + " tm JOIN "
        + TAG_METADATA_OBJECT_RELATION_TABLE_NAME
        + " te ON tm.tag_id = te.tag_id"
        + " WHERE te.metadata_object_id IN "
        + "<foreach collection='metadataObjectIds' item='id' open='(' close=')' separator=','>"
        + "#{id}"
        + "</foreach>"
        + " AND te.deleted_at = 0 AND tm.deleted_at = 0"
        + "</script>";
  }

  /**
   * Builds SQL for batch querying policy relations by object ids across all object types.
   *
   * @param metadataObjectIds The metadata object ids.
   * @return The SQL query.
   */
  public String batchListPolicyRelPOsByMetadataObjectIds(
      @Param("metadataObjectIds") List<Long> metadataObjectIds) {
    return "<script>"
        + "SELECT pe.metadata_object_id as metadataObjectId, pe.metadata_object_type as metadataObjectType,"
        + " pm.policy_id as policyId, pm.policy_name as policyName, pm.policy_type as policyType, pm.metalake_id as metalakeId,"
        + " pm.audit_info as auditInfo, pm.current_version as currentVersion, pm.last_version as lastVersion,"
        + " pm.deleted_at as deletedAt, pvi.id as versionId, pvi.metalake_id as versionMetalakeId, pvi.policy_id as versionPolicyId,"
        + " pvi.version as version, pvi.policy_comment as policyComment, pvi.enabled as enabled, pvi.content as content, pvi.deleted_at as versionDeletedAt"
        + " FROM "
        + POLICY_META_TABLE_NAME
        + " pm JOIN "
        + POLICY_METADATA_OBJECT_RELATION_TABLE_NAME
        + " pe ON pm.policy_id = pe.policy_id"
        + " JOIN "
        + POLICY_VERSION_TABLE_NAME
        + " pvi ON pm.policy_id = pvi.policy_id AND pm.current_version = pvi.version"
        + " WHERE pe.metadata_object_id IN "
        + "<foreach collection='metadataObjectIds' item='id' open='(' close=')' separator=','>"
        + "#{id}"
        + "</foreach>"
        + " AND pe.deleted_at = 0 AND pm.deleted_at = 0 AND pvi.deleted_at = 0"
        + "</script>";
  }
}
