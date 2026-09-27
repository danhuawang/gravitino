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
package org.apache.iceberg;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.metrics.MetricsReporter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

/** Tests publication of admitted metadata and atomic registration overwrite callbacks. */
public class TestMetastoreRegisterTableUtils {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testNewRegistrationCommitsTheAdmittedMetadataObject(boolean overwrite) {
    RegistrationFixture fixture = new RegistrationFixture(false);

    fixture.register(overwrite);

    verify(fixture.operations).commit(isNull(), same(fixture.target));
    verify(fixture.operations, never()).io();
    verifyNoInteractions(fixture.callback);
  }

  @Test
  void testOverwritePassesTheAdmittedMetadataObject() {
    RegistrationFixture fixture = new RegistrationFixture(true);

    fixture.register(true);

    verify(fixture.callback)
        .overwrite(
            eq(fixture.identifier),
            eq("old.metadata.json"),
            eq(fixture.location),
            same(fixture.target));
    verify(fixture.operations, never()).io();
    verify(fixture.operations, never()).commit(any(), any());
    InOrder order = inOrder(fixture.operations, fixture.callback);
    order.verify(fixture.operations).refresh();
    order
        .verify(fixture.callback)
        .overwrite(fixture.identifier, "old.metadata.json", fixture.location, fixture.target);
    order.verify(fixture.operations).refresh();
  }

  @Test
  void testMissingBaseStopsOverwriteBeforeCallback() {
    RegistrationFixture fixture = new RegistrationFixture(true);
    when(fixture.operations.current()).thenReturn(null);

    IllegalArgumentException failure =
        Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.register(true));

    Assertions.assertEquals(
        "Cannot overwrite table ns.t: current metadata is missing", failure.getMessage());
    verifyNoInteractions(fixture.callback);
  }

  @Test
  void testOverwriteFailureIsNotRetriedOrRefreshed() {
    RegistrationFixture fixture = new RegistrationFixture(true);
    RuntimeException failure = new RuntimeException("registration failure");
    doThrow(failure).when(fixture.callback).overwrite(any(), any(), any(), any());

    Assertions.assertSame(
        failure, Assertions.assertThrows(RuntimeException.class, () -> fixture.register(true)));

    verify(fixture.operations).refresh();
    verify(fixture.operations, never()).commit(any(), any());
    verify(fixture.callback).overwrite(any(), any(), any(), same(fixture.target));
  }

  @Test
  void testExistingTableWithoutOverwriteRejectsBeforeOperations() {
    RegistrationFixture fixture = new RegistrationFixture(true);

    Assertions.assertThrows(AlreadyExistsException.class, () -> fixture.register(false));

    verify(fixture.catalog, never()).newTableOps(any());
    verifyNoInteractions(fixture.operations, fixture.callback);
  }

  @Test
  void testOverwriteRequiresMetastoreOperations() {
    RegistrationFixture fixture = new RegistrationFixture(true);
    when(fixture.catalog.newTableOps(fixture.identifier)).thenReturn(mock(TableOperations.class));

    IllegalArgumentException failure =
        Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.register(true));

    Assertions.assertEquals(
        "Cannot overwrite table ns.t: table operations are not metastore-backed",
        failure.getMessage());
    verifyNoInteractions(fixture.callback);
  }

  @Test
  void testArgumentValidationPrecedesExistenceLookup() {
    RegistrationFixture fixture = new RegistrationFixture(true);
    Assertions.assertEquals(
        "Invalid identifier: null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    MetastoreRegisterTableUtils.registerTable(
                        fixture.catalog, null, "", true, null, fixture.callback))
            .getMessage());
    Assertions.assertEquals(
        "Cannot register an empty metadata file location as a table",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    MetastoreRegisterTableUtils.registerTable(
                        fixture.catalog, fixture.identifier, "", true, null, fixture.callback))
            .getMessage());
    Assertions.assertEquals(
        "targetMetadata cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    MetastoreRegisterTableUtils.registerTable(
                        fixture.catalog,
                        fixture.identifier,
                        fixture.location,
                        true,
                        null,
                        fixture.callback))
            .getMessage());
    verify(fixture.catalog, never()).tableExists(any());
    verifyNoInteractions(fixture.operations, fixture.callback);
  }

  private static class RegistrationFixture {
    private final BaseMetastoreCatalog catalog = mock(BaseMetastoreCatalog.class);
    private final BaseMetastoreTableOperations operations =
        mock(BaseMetastoreTableOperations.class);
    private final MetastoreRegisterTableUtils.MetadataLocationOverwrite callback =
        mock(MetastoreRegisterTableUtils.MetadataLocationOverwrite.class);
    private final TableIdentifier identifier = TableIdentifier.of("ns", "t");
    private final String location = "memory://registration/ns/t/v2.metadata.json";
    private final TableMetadata target = mock(TableMetadata.class);

    private RegistrationFixture(boolean exists) {
      when(catalog.isValidIdentifier(identifier)).thenReturn(true);
      when(catalog.tableExists(identifier)).thenReturn(exists);
      when(catalog.newTableOps(identifier)).thenReturn(operations);
      when(catalog.name()).thenReturn("test");
      when(catalog.metricsReporter()).thenReturn(mock(MetricsReporter.class));
      TableMetadata base = mock(TableMetadata.class);
      when(base.metadataFileLocation()).thenReturn("old.metadata.json");
      when(operations.current()).thenReturn(base);
    }

    private Table register(boolean overwrite) {
      return MetastoreRegisterTableUtils.registerTable(
          catalog, identifier, location, overwrite, target, callback);
    }
  }
}
