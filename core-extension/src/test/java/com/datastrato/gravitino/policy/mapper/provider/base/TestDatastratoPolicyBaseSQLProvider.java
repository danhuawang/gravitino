/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.policy.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetadataObjectRelMapper;
import org.junit.jupiter.api.Test;

public class TestDatastratoPolicyBaseSQLProvider {

  @Test
  public void testListAssociatedMetadataObjectsForPolicies() {
    String sql =
        new DatastratoPolicyBaseSQLProvider()
            .listAssociatedMetadataObjectsForPolicies("test_metalake", List.of(1L, 2L));

    assertTrue(
        sql.contains(
            "SELECT pe.policy_id as policyId, pe.metadata_object_id as metadataObjectId,"
                + " pe.metadata_object_type as metadataObjectType"));
    assertTrue(
        sql.contains(
            "FROM "
                + PolicyMetadataObjectRelMapper.POLICY_METADATA_OBJECT_RELATION_TABLE_NAME
                + " pe JOIN "
                + PolicyMetaMapper.POLICY_META_TABLE_NAME
                + " pm ON pe.policy_id = pm.policy_id"));
    assertTrue(
        sql.contains(
            "JOIN " + MetalakeMetaMapper.TABLE_NAME + " mm ON pm.metalake_id = mm.metalake_id"));
    assertTrue(sql.contains("WHERE mm.metalake_name = #{metalakeName}"));
    assertTrue(sql.contains("AND pe.policy_id IN"));
    assertTrue(sql.contains("collection='policyIds'"));
    assertTrue(sql.contains("AND pe.deleted_at = 0 AND pm.deleted_at = 0 AND mm.deleted_at = 0"));
  }
}
