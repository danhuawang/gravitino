/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.policy.mapper.provider;

import com.datastrato.gravitino.policy.mapper.DatastratoPolicyMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Provides enterprise policy MyBatis mappers to relational storage. */
public class DatastratoPolicyMapperPackageProvider implements MapperPackageProvider {

  /**
   * Gets enterprise policy MyBatis mapper classes.
   *
   * @return The mapper classes.
   */
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(DatastratoPolicyMapper.class);
  }
}
