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

package org.apache.gravitino.flink.connector.jdbc.oracle;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.FunctionNotExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.catalog.stats.CatalogColumnStatistics;
import org.apache.flink.table.catalog.stats.CatalogTableStatistics;
import org.apache.flink.table.factories.CatalogFactory;
import org.apache.gravitino.flink.connector.UnsupportPartitionConverter;
import org.apache.gravitino.flink.connector.jdbc.JdbcPropertiesConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GravitinoOracleJdbcCatalog}. */
public class TestGravitinoOracleJdbcCatalog {

  private GravitinoOracleJdbcCatalog catalog;

  @BeforeEach
  public void setUp() {
    CatalogFactory.Context ctx = mock(CatalogFactory.Context.class);
    when(ctx.getName()).thenReturn("test-oracle");
    when(ctx.getOptions())
        .thenReturn(
            Collections.singletonMap(
                JdbcPropertiesConstants.GRAVITINO_JDBC_URL,
                "jdbc:oracle:thin:@//localhost:1521/ORCL"));
    catalog =
        new GravitinoOracleJdbcCatalog(
            ctx,
            "DEFAULT",
            OraclePropertiesConverter.INSTANCE,
            UnsupportPartitionConverter.INSTANCE);
  }

  // ---------------------------------------------------------------------------
  // getFunction — must throw FunctionNotExistException, never UnsupportedOperationException
  // ---------------------------------------------------------------------------

  @Test
  public void testGetFunctionThrowsFunctionNotExistException() {
    ObjectPath functionPath = new ObjectPath("mySchema", "myFunc");
    Assertions.assertThrows(
        FunctionNotExistException.class, () -> catalog.getFunction(functionPath));
  }

  @Test
  public void testGetTableStatisticsReturnsUnknown() throws Exception {
    GravitinoOracleJdbcCatalog spyCatalog = spy(catalog);
    ObjectPath tablePath = new ObjectPath("SC", "T");
    doReturn(true).when(spyCatalog).tableExists(tablePath);
    CatalogTableStatistics stats = spyCatalog.getTableStatistics(tablePath);
    Assertions.assertSame(CatalogTableStatistics.UNKNOWN, stats);
  }

  @Test
  public void testGetTableStatisticsThrowsWhenTableDoesNotExist() {
    GravitinoOracleJdbcCatalog spyCatalog = spy(catalog);
    ObjectPath tablePath = new ObjectPath("SC", "T");
    doReturn(false).when(spyCatalog).tableExists(tablePath);
    Assertions.assertThrows(
        TableNotExistException.class, () -> spyCatalog.getTableStatistics(tablePath));
  }

  @Test
  public void testGetTableColumnStatisticsReturnsUnknown() throws Exception {
    GravitinoOracleJdbcCatalog spyCatalog = spy(catalog);
    ObjectPath tablePath = new ObjectPath("SC", "T");
    doReturn(true).when(spyCatalog).tableExists(tablePath);
    CatalogColumnStatistics stats = spyCatalog.getTableColumnStatistics(tablePath);
    Assertions.assertSame(CatalogColumnStatistics.UNKNOWN, stats);
  }

  @Test
  public void testGetTableColumnStatisticsThrowsWhenTableDoesNotExist() {
    GravitinoOracleJdbcCatalog spyCatalog = spy(catalog);
    ObjectPath tablePath = new ObjectPath("SC", "T");
    doReturn(false).when(spyCatalog).tableExists(tablePath);
    Assertions.assertThrows(
        TableNotExistException.class, () -> spyCatalog.getTableColumnStatistics(tablePath));
  }

  @Test
  public void testGetPartitionStatisticsReturnsUnknown() throws Exception {
    CatalogTableStatistics stats =
        catalog.getPartitionStatistics(
            new ObjectPath("SC", "T"), new CatalogPartitionSpec(Collections.emptyMap()));
    Assertions.assertSame(CatalogTableStatistics.UNKNOWN, stats);
  }

  @Test
  public void testGetPartitionColumnStatisticsReturnsUnknown() throws Exception {
    CatalogColumnStatistics stats =
        catalog.getPartitionColumnStatistics(
            new ObjectPath("SC", "T"), new CatalogPartitionSpec(Collections.emptyMap()));
    Assertions.assertSame(CatalogColumnStatistics.UNKNOWN, stats);
  }

  // ---------------------------------------------------------------------------
  // realCatalog — must throw UnsupportedOperationException
  // ---------------------------------------------------------------------------

  @Test
  public void testRealCatalogThrowsUnsupportedOperationException() {
    Assertions.assertThrows(UnsupportedOperationException.class, () -> catalog.realCatalog());
  }
}
