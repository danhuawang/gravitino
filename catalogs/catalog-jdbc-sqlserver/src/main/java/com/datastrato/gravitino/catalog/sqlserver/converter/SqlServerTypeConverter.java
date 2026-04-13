/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;

/** Type converter for SQL Server catalog. */
public class SqlServerTypeConverter extends JdbcTypeConverter {

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    // TODO: Implement type conversion in PR3
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  public String fromGravitino(Type type) {
    // TODO: Implement type conversion in PR3
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
