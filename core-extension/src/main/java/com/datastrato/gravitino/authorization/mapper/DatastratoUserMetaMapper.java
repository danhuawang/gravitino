/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper;

import java.util.List;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/** Enterprise MyBatis mapper for batch user_meta updates. */
public interface DatastratoUserMetaMapper {

  /**
   * Lists active users under a metalake by name.
   *
   * @param metalakeName The metalake name.
   * @param userNames Distinct user names.
   * @return Matching user rows.
   */
  @SelectProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "listUserMetasByMetalakeNameAndNames")
  List<UserPO> listUserMetasByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName, @Param("userNames") List<String> userNames);

  /**
   * Batch-updates {@code enabled} for users under a metalake. Callers must validate existence and
   * null {@code external_id} first; this statement also requires {@code external_id IS NULL}.
   *
   * @param metalakeName The metalake name.
   * @param userNames Distinct user names.
   * @param enabled Target enabled value.
   * @return Number of updated rows.
   */
  @UpdateProvider(
      type = DatastratoUserMetaSQLProviderFactory.class,
      method = "batchUpdateEnabledByMetalakeNameAndNames")
  int batchUpdateEnabledByMetalakeNameAndNames(
      @Param("metalakeName") String metalakeName,
      @Param("userNames") List<String> userNames,
      @Param("enabled") boolean enabled);
}
