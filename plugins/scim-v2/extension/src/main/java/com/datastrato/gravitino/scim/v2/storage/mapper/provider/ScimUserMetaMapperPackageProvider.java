/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper.provider;

import com.datastrato.gravitino.scim.v2.storage.mapper.ScimUserMetaMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Supplies SCIM v2 user metadata mapper classes. */
public class ScimUserMetaMapperPackageProvider implements MapperPackageProvider {
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(ScimUserMetaMapper.class);
  }
}
