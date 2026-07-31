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

package org.apache.gravitino.flink.connector.jdbc.sqlserver;

import org.apache.flink.table.catalog.Catalog;
import org.apache.gravitino.flink.connector.CatalogPropertiesConverter;
import org.apache.gravitino.flink.connector.PartitionConverter;
import org.apache.gravitino.flink.connector.SchemaAndTablePropertiesConverter;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactory;
import org.apache.gravitino.flink.connector.jdbc.GravitinoJdbcCatalogFactoryOptions;

/** Factory for creating SQL Server JDBC catalog instances. */
public class GravitinoSqlServerJdbcCatalogFactory extends GravitinoJdbcCatalogFactory {

  @Override
  public String gravitinoCatalogProvider() {
    return "jdbc-sqlserver";
  }

  @Override
  public CatalogPropertiesConverter catalogPropertiesConverter() {
    return SqlServerPropertiesConverter.INSTANCE;
  }

  @Override
  public SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter() {
    return SqlServerPropertiesConverter.INSTANCE;
  }

  @Override
  public String factoryIdentifier() {
    return GravitinoJdbcCatalogFactoryOptions.SQLSERVER_IDENTIFIER;
  }

  @Override
  protected Catalog newCatalog(
      Context context,
      String defaultDatabase,
      SchemaAndTablePropertiesConverter schemaAndTablePropertiesConverter,
      PartitionConverter partitionConverter) {
    return new GravitinoSqlServerJdbcCatalog(
        context, defaultDatabase, schemaAndTablePropertiesConverter, partitionConverter);
  }
}
