/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.connection.mapper.provider;

import com.datastrato.gravitino.catalog.connection.mapper.ConnectionTestResultMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;

/** Registers the Enterprise Catalog connection test mapper with relational storage. */
public class ConnectionTestResultMapperPackageProvider implements MapperPackageProvider {

  /**
   * @return The Enterprise Catalog connection test mapper class.
   */
  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(ConnectionTestResultMapper.class);
  }
}
