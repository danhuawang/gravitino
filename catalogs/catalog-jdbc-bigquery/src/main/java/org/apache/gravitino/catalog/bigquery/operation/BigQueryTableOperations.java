/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.bigquery.operation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.gravitino.catalog.jdbc.JdbcColumn;
import org.apache.gravitino.catalog.jdbc.JdbcTable;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;

/** Table operations for BigQuery. */
public class BigQueryTableOperations extends JdbcTableOperations {

  @Override
  protected String generateCreateTableSql(
      String tableName,
      JdbcColumn[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      Index[] indexes) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected boolean getAutoIncrementInfo(ResultSet resultSet) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected Map<String, String> getTableProperties(Connection connection, String tableName) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected List<Index> getIndexes(Connection connection, String databaseName, String tableName) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected Transform[] getTablePartitioning(
      Connection connection, String databaseName, String tableName) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected void correctJdbcTableFields(
      Connection connection,
      String databaseName,
      String tableName,
      JdbcTable.Builder tableBuilder) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected String generateRenameTableSql(String oldTableName, String newTableName) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected String generatePurgeTableSql(String tableName) {
    throw new UnsupportedOperationException(
        "BigQuery does not support purge table in Gravitino, please use drop table");
  }

  @Override
  protected String generateAlterTableSql(
      String databaseName, String tableName, TableChange... changes) {
    throw new NotImplementedException("To be implemented in the future");
  }

  @Override
  protected Distribution getDistributionInfo(
      Connection connection, String databaseName, String tableName) {
    throw new NotImplementedException("To be implemented in the future");
  }
}
