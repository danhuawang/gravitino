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
package org.apache.gravitino.catalog.bigquery;

import org.apache.gravitino.catalog.bigquery.converter.BigQueryColumnDefaultValueConverter;
import org.apache.gravitino.catalog.bigquery.converter.BigQueryExceptionConverter;
import org.apache.gravitino.catalog.bigquery.converter.BigQueryTypeConverter;
import org.apache.gravitino.catalog.bigquery.operation.BigQueryDatabaseOperations;
import org.apache.gravitino.catalog.bigquery.operation.BigQueryTableOperations;
import org.apache.gravitino.catalog.jdbc.JdbcCatalog;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.operation.JdbcTableOperations;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.capability.Capability;

/** Implementation of a BigQuery catalog in Apache Gravitino. */
public class BigQueryCatalog extends JdbcCatalog {

  public static final BigQueryTablePropertiesMetadata BIGQUERY_TABLE_PROPERTIES_META =
      new BigQueryTablePropertiesMetadata();

  @Override
  public String shortName() {
    return "jdbc-bigquery";
  }

  @Override
  public Capability newCapability() {
    return new BigQueryCatalogCapability();
  }

  @Override
  public PropertiesMetadata catalogPropertiesMetadata() throws UnsupportedOperationException {
    return new BigQueryCatalogPropertiesMetadata();
  }

  @Override
  public PropertiesMetadata schemaPropertiesMetadata() throws UnsupportedOperationException {
    return new BigQuerySchemaPropertiesMetadata();
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return BIGQUERY_TABLE_PROPERTIES_META;
  }

  @Override
  protected JdbcExceptionConverter createExceptionConverter() {
    return new BigQueryExceptionConverter();
  }

  @Override
  protected JdbcTypeConverter createJdbcTypeConverter() {
    return new BigQueryTypeConverter();
  }

  @Override
  protected JdbcDatabaseOperations createJdbcDatabaseOperations() {
    return new BigQueryDatabaseOperations();
  }

  @Override
  protected JdbcTableOperations createJdbcTableOperations() {
    return new BigQueryTableOperations();
  }

  @Override
  protected JdbcColumnDefaultValueConverter createJdbcColumnDefaultValueConverter() {
    return new BigQueryColumnDefaultValueConverter();
  }
}
