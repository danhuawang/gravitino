/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider;

import com.datastrato.gravitino.scim.storage.mapper.ScimErrorHistoryMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Supplies SCIM error history mapper classes from the SCIM extension plugin. */
public class ScimErrorHistoryMapperPackageProvider implements MapperPackageProvider {

  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(ScimErrorHistoryMapper.class);
  }
}
