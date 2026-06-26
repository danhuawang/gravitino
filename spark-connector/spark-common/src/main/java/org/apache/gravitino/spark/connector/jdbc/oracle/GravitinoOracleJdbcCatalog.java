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
package org.apache.gravitino.spark.connector.jdbc.oracle;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.jdbc.GravitinoJdbcCatalog;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConverter;
import org.apache.spark.sql.catalyst.analysis.NamespaceAlreadyExistsException;
import org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.catalyst.analysis.NonEmptyNamespaceException;
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.NamespaceChange;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableChange;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.types.StructType;

/**
 * Base Oracle JDBC catalog. Uppercases the namespace in all table operations because Oracle stores
 * schema names (= usernames) in uppercase while Spark's case-insensitive SQL may pass lowercase.
 */
public abstract class GravitinoOracleJdbcCatalog extends GravitinoJdbcCatalog {

  @Override
  protected PropertiesConverter getPropertiesConverter() {
    return JdbcPropertiesConverter.getNoTablePropertiesInstance();
  }

  @Override
  public boolean tableExists(Identifier ident) {
    return super.tableExists(upperCaseNamespace(ident));
  }

  @Override
  public Table loadTable(Identifier ident) throws NoSuchTableException {
    return super.loadTable(upperCaseNamespace(ident));
  }

  @Override
  public Table createTable(
      Identifier ident, StructType schema, Transform[] transforms, Map<String, String> properties)
      throws TableAlreadyExistsException, NoSuchNamespaceException {
    return super.createTable(upperCaseNamespace(ident), schema, transforms, properties);
  }

  @Override
  public Table alterTable(Identifier ident, TableChange... changes) throws NoSuchTableException {
    return super.alterTable(upperCaseNamespace(ident), changes);
  }

  @Override
  public boolean dropTable(Identifier ident) {
    return super.dropTable(upperCaseNamespace(ident));
  }

  @Override
  public boolean purgeTable(Identifier ident) {
    return super.purgeTable(upperCaseNamespace(ident));
  }

  @Override
  public void renameTable(Identifier oldIdent, Identifier newIdent)
      throws NoSuchTableException, TableAlreadyExistsException {
    super.renameTable(upperCaseNamespace(oldIdent), upperCaseNamespace(newIdent));
  }

  @Override
  public Identifier[] listTables(String[] namespace) throws NoSuchNamespaceException {
    return super.listTables(upperCase(namespace));
  }

  @Override
  public Map<String, String> loadNamespaceMetadata(String[] namespace)
      throws NoSuchNamespaceException {
    return super.loadNamespaceMetadata(upperCase(namespace));
  }

  @Override
  public void createNamespace(String[] namespace, Map<String, String> metadata)
      throws NamespaceAlreadyExistsException {
    super.createNamespace(upperCase(namespace), metadata);
  }

  @Override
  public void alterNamespace(String[] namespace, NamespaceChange... changes)
      throws NoSuchNamespaceException {
    super.alterNamespace(upperCase(namespace), changes);
  }

  @Override
  public boolean dropNamespace(String[] namespace, boolean cascade)
      throws NoSuchNamespaceException, NonEmptyNamespaceException {
    return super.dropNamespace(upperCase(namespace), cascade);
  }

  protected static Identifier upperCaseNamespace(Identifier ident) {
    return Identifier.of(upperCase(ident.namespace()), ident.name());
  }

  static String[] upperCase(String[] namespace) {
    return Arrays.stream(namespace).map(s -> s.toUpperCase(Locale.ROOT)).toArray(String[]::new);
  }
}
