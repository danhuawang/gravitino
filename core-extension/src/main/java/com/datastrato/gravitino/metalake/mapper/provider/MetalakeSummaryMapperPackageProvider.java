/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metalake.mapper.provider;

import com.datastrato.gravitino.metalake.mapper.MetalakeSummaryMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Registers the Enterprise metalake summary mapper with relational storage. */
public class MetalakeSummaryMapperPackageProvider implements MapperPackageProvider {

  /**
   * @return The Enterprise metalake summary mapper class.
   */
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(MetalakeSummaryMapper.class);
  }
}
