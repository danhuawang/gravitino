/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.flink.connector.jdbc.sqlserver;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.catalog.stats.CatalogColumnStatistics;
import org.apache.flink.table.catalog.stats.CatalogTableStatistics;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.gravitino.flink.connector.UnsupportPartitionConverter;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GravitinoSqlServerJdbcCatalog}. */
public class TestGravitinoSqlServerJdbcCatalog {

  private GravitinoSqlServerJdbcCatalog catalog;

  @BeforeEach
  public void setUp() {
    CatalogFactory.Context ctx = mock(CatalogFactory.Context.class);
    when(ctx.getName()).thenReturn("test-sqlserver");
    when(ctx.getOptions())
        .thenReturn(
            Collections.singletonMap(
                JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
                "jdbc:sqlserver://localhost:1433;databaseName=test"));
    catalog =
        new GravitinoSqlServerJdbcCatalog(
            ctx,
            "dbo",
            SqlServerPropertiesConverter.INSTANCE,
            UnsupportPartitionConverter.INSTANCE) {};
  }

  @Test
  public void testGetTableStatisticsReturnsUnknown() throws Exception {
    ObjectPath tablePath = new ObjectPath("dbo", "my_table");
    GravitinoSqlServerJdbcCatalog spyCatalog = spy(catalog);
    doReturn(true).when(spyCatalog).tableExists(tablePath);
    Assertions.assertSame(CatalogTableStatistics.UNKNOWN, spyCatalog.getTableStatistics(tablePath));
  }

  @Test
  public void testGetTableStatisticsThrowsWhenTableDoesNotExist() {
    ObjectPath tablePath = new ObjectPath("dbo", "missing");
    GravitinoSqlServerJdbcCatalog spyCatalog = spy(catalog);
    doReturn(false).when(spyCatalog).tableExists(tablePath);
    Assertions.assertThrows(
        TableNotExistException.class, () -> spyCatalog.getTableStatistics(tablePath));
  }

  @Test
  public void testGetTableColumnStatisticsReturnsUnknown() throws Exception {
    ObjectPath tablePath = new ObjectPath("dbo", "my_table");
    GravitinoSqlServerJdbcCatalog spyCatalog = spy(catalog);
    doReturn(true).when(spyCatalog).tableExists(tablePath);
    Assertions.assertSame(
        CatalogColumnStatistics.UNKNOWN, spyCatalog.getTableColumnStatistics(tablePath));
  }

  @Test
  public void testGetTableColumnStatisticsThrowsWhenTableDoesNotExist() {
    ObjectPath tablePath = new ObjectPath("dbo", "missing");
    GravitinoSqlServerJdbcCatalog spyCatalog = spy(catalog);
    doReturn(false).when(spyCatalog).tableExists(tablePath);
    Assertions.assertThrows(
        TableNotExistException.class, () -> spyCatalog.getTableColumnStatistics(tablePath));
  }
}
