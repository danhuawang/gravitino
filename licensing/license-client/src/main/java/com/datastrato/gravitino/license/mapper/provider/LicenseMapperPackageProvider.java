/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper.provider;

import com.datastrato.gravitino.license.mapper.LicenseNodeMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

public class LicenseMapperPackageProvider implements MapperPackageProvider {
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(LicenseNodeMapper.class);
  }
}
