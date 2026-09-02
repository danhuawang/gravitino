/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider;

import com.datastrato.gravitino.scim.storage.mapper.ScimGroupMetaMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Supplies SCIM group metadata mapper classes. */
public class ScimGroupMetaMapperPackageProvider implements MapperPackageProvider {
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(ScimGroupMetaMapper.class);
  }
}
