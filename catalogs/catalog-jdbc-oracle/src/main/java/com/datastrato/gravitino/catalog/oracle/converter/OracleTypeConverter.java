/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;

/** Type converter for Oracle. */
public class OracleTypeConverter extends JdbcTypeConverter {

  static final String NUMBER = "NUMBER";
  static final String VARCHAR2 = "VARCHAR2";
  static final String CHAR = "CHAR";
  static final String NVARCHAR2 = "NVARCHAR2";
  static final String NCHAR = "NCHAR";
  static final String CLOB = "CLOB";
  static final String NCLOB = "NCLOB";
  static final String BLOB = "BLOB";
  static final String FLOAT = "FLOAT";
  static final String BINARY_FLOAT = "BINARY_FLOAT";
  static final String BINARY_DOUBLE = "BINARY_DOUBLE";
  static final String RAW = "RAW";
  static final String LONG_RAW = "LONG RAW";
  static final String TIMESTAMP = "TIMESTAMP";
  static final String DATE = "DATE";

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    throw new UnsupportedOperationException("To be implemented in the future");
  }

  @Override
  public String fromGravitino(Type type) {
    throw new UnsupportedOperationException("To be implemented in the future");
  }
}
