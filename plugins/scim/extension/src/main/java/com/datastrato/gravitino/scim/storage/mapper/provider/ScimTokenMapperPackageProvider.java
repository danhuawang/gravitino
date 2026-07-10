/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider;

import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Supplies SCIM token storage mapper classes from the SCIM extension plugin. */
public class ScimTokenMapperPackageProvider implements MapperPackageProvider {

  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(ScimTokenMetaMapper.class);
  }
}
