/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper.provider;

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
    return ImmutableList.of(DatastratoSecurableObjectMapper.class, DatastratoUserMetaMapper.class);
  }
}
