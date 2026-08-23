/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.tag.mapper.provider;

import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Provides enterprise tag and policy MyBatis mappers to relational storage. */
public class DatastratoTagPolicyMapperPackageProvider implements MapperPackageProvider {

  /**
   * Gets enterprise tag/policy MyBatis mapper classes.
   *
   * @return The mapper classes.
   */
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(DatastratoTagPolicyMetadataObjectMapper.class);
  }
}
