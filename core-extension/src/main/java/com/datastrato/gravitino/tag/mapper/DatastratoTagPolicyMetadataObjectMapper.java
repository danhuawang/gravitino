/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.tag.mapper;

import com.datastrato.gravitino.tag.po.DatastratoPolicyRelPO;
import com.datastrato.gravitino.tag.po.DatastratoTagRelPO;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Enterprise MyBatis mapper for batch tag and policy metadata object queries. */
public interface DatastratoTagPolicyMetadataObjectMapper {

  /**
   * Batch lists tag relations by metadata object ids across all object types.
   *
   * @param metadataObjectIds The list of metadata object ids.
   * @return The list of tag relation POs.
   */
  @SelectProvider(
      type = DatastratoTagPolicyMetadataObjectSQLProviderFactory.class,
      method = "batchListTagRelPOsByMetadataObjectIds")
  List<DatastratoTagRelPO> batchListTagRelPOsByMetadataObjectIds(
      @Param("metadataObjectIds") List<Long> metadataObjectIds);

  /**
   * Batch lists policy relations by metadata object ids across all object types.
   *
   * @param metadataObjectIds The list of metadata object ids.
   * @return The list of policy relation POs.
   */
  @SelectProvider(
      type = DatastratoTagPolicyMetadataObjectSQLProviderFactory.class,
      method = "batchListPolicyRelPOsByMetadataObjectIds")
  List<DatastratoPolicyRelPO> batchListPolicyRelPOsByMetadataObjectIds(
      @Param("metadataObjectIds") List<Long> metadataObjectIds);
}
