/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider;

import com.datastrato.gravitino.scim.storage.mapper.JitIdentityMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Provides the global JWT-discovered identity mapper. */
public class JitIdentityMapperPackageProvider implements MapperPackageProvider {

  /** Returns the JIT identity mapper class. */
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(JitIdentityMapper.class);
  }
}
