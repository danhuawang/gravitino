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
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.SparkTransformConverter;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTable;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTable;
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * Oracle-specific Spark table.
 *
 * <p>{@link SparkJdbcTable#schema()} reports Gravitino's logical schema, with types derived from
 * Gravitino's column metadata (e.g. {@code NUMBER(10)} mapped to {@code IntegerType}) rather than
 * the underlying JDBC driver's generic type mapping (which would report {@code DecimalType} for the
 * same column). This class keeps those Gravitino-derived types but folds the field names to their
 * physical Oracle form (via {@link OracleNameFolding#toPhysicalName}), since Spark's write path
 * ({@code INSERT INTO}, {@code CREATE TABLE ... AS SELECT}, {@code DataFrameWriter}) builds its
 * JDBC SQL directly from {@link #schema()} without going through Gravitino's name folding, and
 * needs to see the column names that physically exist in Oracle. Spark's column resolution is
 * case-insensitive by default, so reads (e.g. {@code SELECT id FROM t}) still work against this
 * physical schema.
 */
public class SparkOracleJdbcTable extends SparkJdbcTable {

  public SparkOracleJdbcTable(
      Identifier identifier,
      Table gravitinoTable,
      JDBCTable jdbcTable,
      JDBCTableCatalog jdbcTableCatalog,
      PropertiesConverter propertiesConverter,
      SparkTransformConverter sparkTransformConverter,
      SparkTypeConverter sparkTypeConverter) {
    super(
        identifier,
        gravitinoTable,
        jdbcTable,
        jdbcTableCatalog,
        propertiesConverter,
        sparkTransformConverter,
        sparkTypeConverter);
  }

  @Override
  public StructType schema() {
    StructField[] physicalFields =
        Arrays.stream(super.schema().fields())
            .map(
                field ->
                    new StructField(
                        OracleNameFolding.toPhysicalName(field.name()),
                        field.dataType(),
                        field.nullable(),
                        field.metadata()))
            .toArray(StructField[]::new);
    return new StructType(physicalFields);
  }
}
