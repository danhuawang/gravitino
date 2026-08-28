/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper.provider;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoRoleAssignmentMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoSecurableObjectMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Provides enterprise authorization MyBatis mappers to relational storage. */
public class DatastratoAuthorizationMapperPackageProvider implements MapperPackageProvider {
  /**
   * Gets enterprise authorization MyBatis mapper classes.
   *
   * @return The mapper classes.
   */
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(
        DatastratoRoleAssignmentMapper.class,
        DatastratoSecurableObjectMapper.class,
        DatastratoUserMetaMapper.class,
        DatastratoGroupMetaMapper.class);
  }
}
