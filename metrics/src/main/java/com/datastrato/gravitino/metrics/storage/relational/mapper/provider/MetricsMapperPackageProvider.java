/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper.provider;

import com.datastrato.gravitino.metrics.storage.relational.mapper.MetricDataMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

public class MetricsMapperPackageProvider implements MapperPackageProvider {
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(MetricDataMapper.class);
  }
}
