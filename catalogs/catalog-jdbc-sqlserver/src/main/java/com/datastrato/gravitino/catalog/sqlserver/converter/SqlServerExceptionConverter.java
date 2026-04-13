/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.converter;

import java.sql.SQLException;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;

/** Exception converter for SQL Server catalog. */
public class SqlServerExceptionConverter extends JdbcExceptionConverter {

  @SuppressWarnings("FormatStringAnnotation")
  @Override
  public GravitinoRuntimeException toGravitinoException(SQLException se) {
    // TODO: Implement exception conversion in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
