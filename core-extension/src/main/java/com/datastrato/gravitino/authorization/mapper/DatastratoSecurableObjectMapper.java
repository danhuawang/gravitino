/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper;

import java.util.List;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

/** Enterprise MyBatis mapper for authorization securable object queries. */
public interface DatastratoSecurableObjectMapper {

  /**
   * Lists securable objects by role ids.
   *
   * @param roleIds The role ids.
   * @return The securable objects.
   */
  @SelectProvider(
      type = DatastratoSecurableObjectSQLProviderFactory.class,
      method = "listSecurableObjectsByRoleIds")
  List<SecurableObjectPO> listSecurableObjectsByRoleIds(@Param("roleIds") List<Long> roleIds);
}
