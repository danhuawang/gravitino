/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.iceberg.hive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.ClientPool;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.apache.thrift.TException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class TestHiveCatalogWithMetadataLocationSupport {

  private static final TableIdentifier IDENTIFIER = TableIdentifier.of(Namespace.of("ns"), "table");

  @Test
  void testLoadFields() {
    HiveCatalogWithMetadataLocationSupport catalog = new HiveCatalogWithMetadataLocationSupport();
    Assertions.assertDoesNotThrow(() -> catalog.initialize("hive", Collections.emptyMap()));
  }

  @Test
  void testRegisterTableCommitsTheParsedObjectWithoutRereadingTheFile() throws Exception {
    RegistrationFixture fixture = new RegistrationFixture(null);

    fixture.catalog.registerTable(IDENTIFIER, fixture.target, false);

    verify(fixture.operations).commit(isNull(), same(fixture.target));
    verify(fixture.operations, never()).io();
  }

  @Test
  void testRegisterTablePublishesEncryptedFileAsGiven() throws Exception {
    // Policy admission happens above the catalog; the catalog publishes what it was handed.
    RegistrationFixture fixture = new RegistrationFixture("kms-key-1");

    fixture.catalog.registerTable(IDENTIFIER, fixture.target, false);

    ArgumentCaptor<TableMetadata> committed = ArgumentCaptor.forClass(TableMetadata.class);
    verify(fixture.operations).commit(isNull(), committed.capture());
    Assertions.assertEquals(
        "kms-key-1", committed.getValue().properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  @Test
  void testOverwriteRepointsPointerKeyAndHashInOneHmsMutation() throws Exception {
    OverwriteFixture fixture = new OverwriteFixture("kms-key-1", "kms-key-1", null);

    fixture.catalog.registerTable(IDENTIFIER, fixture.target, true);

    ArgumentCaptor<Table> persisted = ArgumentCaptor.forClass(Table.class);
    verify(fixture.operations).persistTable(persisted.capture(), anyBoolean(), any());
    Map<String, String> parameters = persisted.getValue().getParameters();
    Assertions.assertEquals(
        fixture.targetLocation,
        parameters.get(BaseMetastoreTableOperations.METADATA_LOCATION_PROP));
    Assertions.assertEquals("kms-key-1", parameters.get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(
        IcebergMetadataIntegrity.metadataHash(fixture.target),
        parameters.get(BaseMetastoreTableOperations.METADATA_HASH_PROP));
  }

  @Test
  void testOverwriteRejectsKeyChangeBeforeTouchingHms() throws Exception {
    OverwriteFixture fixture = new OverwriteFixture("kms-key-1", "kms-key-2", null);

    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> fixture.catalog.registerTable(IDENTIFIER, fixture.target, true));

    verify(fixture.operations, never()).persistTable(any(), anyBoolean(), any());
  }

  @Test
  void testOverwriteFailsClosedWhenCurrentStateDoesNotVerify() throws Exception {
    String tamperedHash = IcebergMetadataIntegrity.metadataHash(metadataWithLocation("x", "k"));
    OverwriteFixture fixture = new OverwriteFixture("kms-key-1", "kms-key-1", tamperedHash);

    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () -> fixture.catalog.registerTable(IDENTIFIER, fixture.target, true));

    verify(fixture.operations, never()).persistTable(any(), anyBoolean(), any());
  }

  /**
   * A catalog whose Hive table operations are mocked around an existing table, so the overwrite
   * hook runs its lock-verify-persist sequence without a real HMS.
   */
  private static final class OverwriteFixture {
    private final HiveCatalogWithMetadataLocationSupport catalog;
    private final HiveTableOperations operations = mock(HiveTableOperations.class);
    private final String targetLocation;
    private final TableMetadata target;

    /**
     * @param hmsHash the hash HMS holds for the current metadata; {@code null} means the correct
     *     one
     */
    private OverwriteFixture(
        @Nullable String currentKeyId, @Nullable String targetKeyId, @Nullable String hmsHash)
        throws Exception {
      String tag = currentKeyId + "-" + targetKeyId + "-" + (hmsHash == null ? "ok" : "bad");
      String currentLocation = "memory://overwrite/" + tag + "/v1.metadata.json";
      targetLocation = "memory://overwrite/" + tag + "/v2.metadata.json";
      TableMetadata current = metadataWithLocation(currentLocation, currentKeyId);
      target = metadataWithLocation(targetLocation, targetKeyId);

      Table hmsTable =
          icebergTable(
              currentLocation,
              currentKeyId,
              hmsHash != null ? hmsHash : IcebergMetadataIntegrity.metadataHash(current));
      when(operations.current()).thenReturn(current);
      when(operations.lockObject(current)).thenReturn(new NoLock());
      when(operations.loadHmsTable()).thenReturn(hmsTable);
      when(operations.maxHiveTablePropertySize()).thenReturn(32672L);

      HiveCatalogWithMetadataLocationSupport real =
          catalogWithHmsClient(mock(IMetaStoreClient.class));
      real.setConf(new Configuration());
      catalog = spy(real);
      doReturn(operations).when(catalog).newTableOps(same(IDENTIFIER));
      doReturn(true).when(catalog).tableExists(same(IDENTIFIER));
      doReturn("hive").when(catalog).name();
    }
  }

  /** A catalog whose table operations are mocked so registration never reaches a real HMS. */
  private static final class RegistrationFixture {
    private final HiveCatalogWithMetadataLocationSupport catalog;
    private final BaseMetastoreTableOperations operations =
        mock(BaseMetastoreTableOperations.class);
    private final TableMetadata target;

    private RegistrationFixture(@Nullable String keyId) throws Exception {
      String location =
          "memory://registration/ns/table/"
              + (keyId == null ? "plain" : "encrypted")
              + ".metadata.json";
      target = metadataWithLocation(location, keyId);
      // No current metadata: the table does not exist yet.
      when(operations.current()).thenReturn(null);

      HiveCatalogWithMetadataLocationSupport real =
          catalogWithHmsClient(mock(IMetaStoreClient.class));
      catalog = spy(real);
      doReturn(operations).when(catalog).newTableOps(same(IDENTIFIER));
      doReturn(false).when(catalog).tableExists(same(IDENTIFIER));
      doReturn("hive").when(catalog).name();
    }
  }

  private static TableMetadata metadataWithLocation(
      String metadataLocation, @Nullable String keyId) {
    Map<String, String> properties =
        keyId == null
            ? Collections.singletonMap(TableProperties.FORMAT_VERSION, "3")
            : ImmutableMap.of(
                TableProperties.FORMAT_VERSION, "3", TableProperties.ENCRYPTION_TABLE_KEY, keyId);
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get())),
            PartitionSpec.unpartitioned(),
            "file:/warehouse/ns/table",
            properties);
    return TableMetadataParser.fromJson(metadataLocation, TableMetadataParser.toJson(metadata));
  }

  private static Table icebergTable(
      @Nullable String metadataLocation, @Nullable String keyId, @Nullable String metadataHash) {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(
        BaseMetastoreTableOperations.TABLE_TYPE_PROP,
        BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE);
    parameters.put(BaseMetastoreTableOperations.METADATA_LOCATION_PROP, metadataLocation);
    if (keyId != null) {
      parameters.put(TableProperties.ENCRYPTION_TABLE_KEY, keyId);
    }
    if (metadataHash != null) {
      parameters.put(BaseMetastoreTableOperations.METADATA_HASH_PROP, metadataHash);
    }
    Table table = new Table();
    table.setParameters(parameters);
    return table;
  }

  private static HiveCatalogWithMetadataLocationSupport catalogWithHmsClient(
      IMetaStoreClient client) throws Exception {
    ClientPool<IMetaStoreClient, TException> clients = mockClientPool();
    when(clients.run(any())).thenAnswer(invocation -> invoke(invocation.getArgument(0), client));
    HiveCatalogWithMetadataLocationSupport catalog = new HiveCatalogWithMetadataLocationSupport();
    FieldUtils.writeField(catalog, "metaClients", clients, true);
    return catalog;
  }

  @SuppressWarnings("unchecked")
  private static ClientPool<IMetaStoreClient, TException> mockClientPool() {
    return mock(ClientPool.class);
  }

  private static Object invoke(
      ClientPool.Action<?, IMetaStoreClient, TException> action, IMetaStoreClient client)
      throws TException, InterruptedException {
    return action.run(client);
  }
}
