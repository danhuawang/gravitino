/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.policy.mapper;

import java.util.List;
import org.apache.gravitino.storage.relational.po.PolicyMetadataObjectRelPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Enterprise MyBatis mapper for policy relational queries. */
public interface DatastratoPolicyMapper {

  /**
   * Lists associated metadata objects for the selected policies under a metalake.
   *
   * @param metalakeName The metalake name.
   * @param policyIds The policy IDs to query.
   * @return The policy and metadata object relations.
   */
  @SelectProvider(
      type = DatastratoPolicySQLProviderFactory.class,
      method = "listAssociatedMetadataObjectsForPolicies")
  List<PolicyMetadataObjectRelPO> listAssociatedMetadataObjectsForPolicies(
      @Param("metalakeName") String metalakeName, @Param("policyIds") List<Long> policyIds);
}
