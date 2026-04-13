/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.exceptions.NoSuchSchemaException;

/** Schema operations for SQL Server catalog. */
public class SqlServerSchemaOperations extends JdbcDatabaseOperations {

  @Override
  public List<String> listDatabases() {
    // TODO: Implement schema listing in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  public JdbcSchema load(String schema) throws NoSuchSchemaException {
    // TODO: Implement schema loading in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  protected String generateCreateDatabaseSql(
      String schema, String comment, Map<String, String> properties) {
    // TODO: Implement CREATE SCHEMA SQL generation in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  protected String generateDropDatabaseSql(String schema, boolean cascade) {
    // TODO: Implement DROP SCHEMA SQL generation in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  protected boolean supportSchemaComment() {
    return false;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    // TODO: Implement system schema set in PR2
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
