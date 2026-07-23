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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.ConnectorConstants;
import org.apache.gravitino.spark.connector.SparkTransformConverter;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConverter;
import org.apache.gravitino.spark.connector.jdbc.SparkJdbcTypeConverter;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions;
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTable;
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSparkOracleJdbcTable {

  @Test
  void testSchemaUppercasesFieldNamesButKeepsGravitinoTypes() {
    Column[] gravitinoColumns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), null, false, false, null),
          Column.of("name", Types.VarCharType.of(32), "a comment", true, false, null)
        };
    Table gravitinoTable = mock(Table.class);
    when(gravitinoTable.name()).thenReturn("mytable");
    when(gravitinoTable.columns()).thenReturn(gravitinoColumns);

    // The underlying JDBC driver's raw schema is intentionally different (e.g. a generic decimal
    // type instead of Gravitino's precision-aware Integer mapping) to prove schema() does not fall
    // back to it.
    StructType physicalDriverSchema =
        new StructType(
            new StructField[] {
              new StructField("ID", DataTypes.createDecimalType(10, 0), true, null),
              new StructField("NAME", DataTypes.StringType, true, null)
            });
    JDBCTable jdbcTable = mock(JDBCTable.class);
    when(jdbcTable.schema()).thenReturn(physicalDriverSchema);
    when(jdbcTable.jdbcOptions()).thenReturn(mock(JDBCOptions.class));
    JDBCTableCatalog jdbcTableCatalog = mock(JDBCTableCatalog.class);

    Identifier identifier = Identifier.of(new String[] {"myschema"}, "mytable");
    SparkOracleJdbcTable table =
        new SparkOracleJdbcTable(
            identifier,
            gravitinoTable,
            jdbcTable,
            jdbcTableCatalog,
            JdbcPropertiesConverter.getNoTablePropertiesInstance(),
            new SparkTransformConverter(false),
            new SparkJdbcTypeConverter());

    StructType schema = table.schema();
    Assertions.assertEquals(2, schema.fields().length);

    StructField idField = schema.fields()[0];
    Assertions.assertEquals("ID", idField.name());
    Assertions.assertEquals(DataTypes.IntegerType, idField.dataType());
    Assertions.assertFalse(idField.nullable());

    StructField nameField = schema.fields()[1];
    Assertions.assertEquals("NAME", nameField.name());
    Assertions.assertEquals(DataTypes.StringType, nameField.dataType());
    Assertions.assertTrue(nameField.nullable());
    Assertions.assertEquals(
        "a comment", nameField.metadata().getString(ConnectorConstants.COMMENT));
  }

  @Test
  void testSchemaPreservesCaseForQuotedColumnNames() {
    Column[] gravitinoColumns =
        new Column[] {Column.of("\"userId\"", Types.IntegerType.get(), null, false, false, null)};
    Table gravitinoTable = mock(Table.class);
    when(gravitinoTable.name()).thenReturn("\"MyTable\"");
    when(gravitinoTable.columns()).thenReturn(gravitinoColumns);

    StructType physicalDriverSchema =
        new StructType(
            new StructField[] {new StructField("userId", DataTypes.LongType, true, null)});
    JDBCTable jdbcTable = mock(JDBCTable.class);
    when(jdbcTable.schema()).thenReturn(physicalDriverSchema);
    when(jdbcTable.jdbcOptions()).thenReturn(mock(JDBCOptions.class));
    JDBCTableCatalog jdbcTableCatalog = mock(JDBCTableCatalog.class);

    Identifier identifier = Identifier.of(new String[] {"myschema"}, "\"MyTable\"");
    SparkOracleJdbcTable table =
        new SparkOracleJdbcTable(
            identifier,
            gravitinoTable,
            jdbcTable,
            jdbcTableCatalog,
            JdbcPropertiesConverter.getNoTablePropertiesInstance(),
            new SparkTransformConverter(false),
            new SparkJdbcTypeConverter());

    StructType schema = table.schema();
    Assertions.assertEquals(1, schema.fields().length);
    Assertions.assertEquals("userId", schema.fields()[0].name());
  }
}
