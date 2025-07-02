/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.storage.relational.mapper.provider;

import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

public class DatastratoMapperPackageProvider implements MapperPackageProvider {
  private static final String DATASTRATO_MAPPER_PACKAGE =
      "com.datastrato.gravitino.storage.relational.mapper";

  @Override
  public String getPackageName() {
    return DATASTRATO_MAPPER_PACKAGE;
  }
}
