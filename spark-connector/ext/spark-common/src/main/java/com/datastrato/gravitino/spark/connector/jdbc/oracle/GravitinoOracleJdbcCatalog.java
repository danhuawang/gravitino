/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc.oracle;

import java.util.Arrays;
import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.SparkTransformConverter;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.gravitino.spark.connector.jdbc.GravitinoJdbcCatalog;
import org.apache.gravitino.spark.connector.jdbc.JdbcPropertiesConverter;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTable;
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog;

/**
 * Base Oracle JDBC catalog.
 *
 * <p>Gravitino stores Oracle identifiers as logical names following Oracle's own quoting
 * convention: a quoted logical name (e.g. {@code "MyTable"}) preserves its exact case, while an
 * unquoted logical name is folded to uppercase. The Gravitino server folds any client-supplied case
 * server-side, so no folding is needed when calling Gravitino APIs.
 *
 * <p>The wrapped Spark {@code JDBCTableCatalog} connects directly to Oracle and uses Oracle's
 * quoting rules, so it must receive the physical identifiers. Overriding {@link #loadSparkTable}
 * and {@link #invalidateTable} converts logical identifiers to physical ones (via {@link
 * OracleNameFolding#toPhysicalName}) before delegating to the underlying Spark catalog.
 *
 * <p>Spark's write path ({@code INSERT INTO}, {@code CREATE TABLE ... AS SELECT}, {@code
 * DataFrameWriter}) goes through Spark's built-in JDBC data source directly and builds its SQL from
 * the table's Spark-visible schema, not through Gravitino's name folding. {@link #createSparkTable}
 * therefore returns a {@link SparkOracleJdbcTable}, which reports the physical Oracle schema
 * instead of Gravitino's logical one, so that generated SQL matches the physical columns.
 */
public abstract class GravitinoOracleJdbcCatalog extends GravitinoJdbcCatalog {

  @Override
  protected PropertiesConverter getPropertiesConverter() {
    return JdbcPropertiesConverter.getNoTablePropertiesInstance();
  }

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkOracleTypeConverter();
  }

  @Override
  protected Table createSparkTable(
      Identifier identifier,
      org.apache.gravitino.rel.Table gravitinoTable,
      Table sparkTable,
      TableCatalog sparkCatalog,
      PropertiesConverter propertiesConverter,
      SparkTransformConverter sparkTransformConverter,
      SparkTypeConverter sparkTypeConverter) {
    return new SparkOracleJdbcTable(
        identifier,
        gravitinoTable,
        (JDBCTable) sparkTable,
        (JDBCTableCatalog) sparkCatalog,
        propertiesConverter,
        sparkTransformConverter,
        sparkTypeConverter);
  }

  @Override
  protected Table loadSparkTable(Identifier ident) {
    return super.loadSparkTable(toPhysicalIdentifier(ident));
  }

  @Override
  public void invalidateTable(Identifier ident) {
    super.invalidateTable(toPhysicalIdentifier(ident));
  }

  /**
   * Converts a logical identifier to the physical Oracle identifier, for use when calling the
   * underlying Spark {@code JDBCTableCatalog}.
   */
  protected static Identifier toPhysicalIdentifier(Identifier ident) {
    String[] physicalNamespace =
        Arrays.stream(ident.namespace())
            .map(OracleNameFolding::toPhysicalName)
            .toArray(String[]::new);
    return Identifier.of(physicalNamespace, OracleNameFolding.toPhysicalName(ident.name()));
  }
}
