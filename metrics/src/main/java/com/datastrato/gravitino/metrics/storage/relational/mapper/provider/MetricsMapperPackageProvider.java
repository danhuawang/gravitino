/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.mapper.provider;

import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

public class MetricsMapperPackageProvider implements MapperPackageProvider {
  private static final String METRICS_MAPPER_PACKAGE =
      "com.datastrato.gravitino.metrics.storage.relational.mapper";

  @Override
  public String getPackageName() {
    return METRICS_MAPPER_PACKAGE;
  }
}
