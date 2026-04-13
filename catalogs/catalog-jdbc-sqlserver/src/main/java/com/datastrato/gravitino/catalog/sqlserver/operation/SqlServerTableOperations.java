/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.operation.DatabaseOperation;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.catalog.jdbc.operation.RequireDatabaseOperation;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;

/** Table operations for SQL Server catalog. */
@SuppressWarnings("UnusedVariable")
public class SqlServerTableOperations extends JdbcTableOperations
    implements RequireDatabaseOperation {

  private SqlServerSchemaOperations schemaOperations;

  @Override
  public void setDatabaseOperation(DatabaseOperation databaseOperation) {
    this.schemaOperations = (SqlServerSchemaOperations) databaseOperation;
  }

  @Override
  protected String generateCreateTableSql(
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {
    // TODO: Implement CREATE TABLE SQL generation in PR3
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    // TODO: Implement RENAME TABLE SQL generation in PR3
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  protected String generateDropTableSql(String tableName) {
    // TODO: Implement DROP TABLE SQL generation in PR3
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    throw new UnsupportedOperationException("SQL Server does not support purge table");
  }

  @Override
  protected String generateAlterTableSql(
      String schemaName, String tableName, TableChange... changes) {
    // TODO: Implement ALTER TABLE SQL generation in PR3
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
