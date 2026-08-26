/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static com.datastrato.gravitino.TestBasePropertiesMetadata.COMMENT_KEY;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.apache.gravitino.Entity.EntityType.SCHEMA;
import static org.apache.gravitino.Entity.EntityType.TABLE;
import static org.apache.gravitino.StringIdentifier.ID_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.TestColumn;
import com.datastrato.gravitino.preview.TrinoJdbcDataPreviewOperator;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.connector.HiddenPropertyMaskUtils;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestDatastratoTableOperationDispatcher extends TestDatastratoOperationDispatcher {
  static DatastratoTableOperationDispatcher tableOperationDispatcher;
  static DatastratoSchemaOperationDispatcher schemaOperationDispatcher;
  static TrinoJdbcDataPreviewOperator trinoJdbcDataPreviewOperator;

  @BeforeAll
  public static void initialize() throws IllegalAccessException {
    schemaOperationDispatcher =
        new DatastratoSchemaOperationDispatcher(catalogManager, entityStore, idGenerator);
    trinoJdbcDataPreviewOperator = mock(TrinoJdbcDataPreviewOperator.class);
    tableOperationDispatcher =
        new DatastratoTableOperationDispatcher(
            catalogManager,
            entityStore,
            idGenerator,
            trinoJdbcDataPreviewOperator,
            () -> schemaOperationDispatcher);

    Config config = mock(Config.class);
    doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Test
  public void testCreateAndListTables() throws IOException {
    Namespace tableNs = Namespace.of(metalake, catalog, "schema41");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    schemaOperationDispatcher.createSchema(NameIdentifier.of(tableNs.levels()), "comment", props);

    NameIdentifier tableIdent1 = NameIdentifier.of(tableNs, "table1");
    Column[] columns =
        new Column[] {
          Column.of("col1", Types.StringType.get()), Column.of("col2", Types.StringType.get())
        };

    Table table1 =
        tableOperationDispatcher.createTable(
            tableIdent1, columns, "comment", props, new Transform[0]);
    Assertions.assertEquals("table1", table1.name());
    Assertions.assertEquals("comment", table1.comment());
    testProperties(props, table1.properties());
    Assertions.assertEquals(0, table1.partitioning().length);
    Assertions.assertArrayEquals(columns, table1.columns());

    // Test required table properties exception
    Map<String, String> illegalTableProperties =
        new HashMap<String, String>() {
          {
            put("k2", "v2");
          }
        };
    testPropertyException(
        () ->
            tableOperationDispatcher.createTable(
                tableIdent1, columns, "comment", illegalTableProperties, new Transform[0]),
        "Properties or property prefixes are required and must be set");

    // Test reserved table properties exception
    illegalTableProperties.put(COMMENT_KEY, "table comment");
    illegalTableProperties.put(ID_KEY, "gravitino.v1.uidfdsafdsa");
    testPropertyException(
        () ->
            tableOperationDispatcher.createTable(
                tableIdent1, columns, "comment", illegalTableProperties, new Transform[0]),
        "Properties or property prefixes are reserved and cannot be set",
        "comment",
        "gravitino.identifier");

    // Check if the Table entity is stored in the EntityStore
    TableEntity tableEntity = entityStore.get(tableIdent1, TABLE, TableEntity.class);
    Assertions.assertNotNull(tableEntity);
    Assertions.assertEquals("table1", tableEntity.name());

    Assertions.assertTrue(
        !table1.properties().containsKey(ID_KEY)
            || HiddenPropertyMaskUtils.MASKED_VALUE.equals(table1.properties().get(ID_KEY)));

    // test listTables
    Optional<NameIdentifier> ident1 =
        Arrays.stream(tableOperationDispatcher.listTables(tableNs))
            .filter(s -> s.name().equals("table1"))
            .findFirst();
    Assertions.assertTrue(ident1.isPresent());

    // test listEntities
    Optional<TableEntity> entity =
        tableOperationDispatcher.listEntities(tableNs).stream()
            .filter(e -> e.name().equals("table1"))
            .findFirst();
    Assertions.assertTrue(entity.isPresent());
    Assertions.assertEquals(tableEntity, entity.get());

    // test listEntities with non-existent namespace
    Namespace nonExistentNs = Namespace.of(metalake, catalog, "nonExistent");
    Exception exception =
        Assertions.assertThrows(
            NoSuchSchemaException.class,
            () -> tableOperationDispatcher.listEntities(nonExistentNs));
    Assertions.assertEquals(
        "Schema does not exist: metalake.catalog.nonExistent", exception.getMessage());

    // Test when the entity store failed to put the table entity
    doThrow(new IOException()).when(entityStore).put(any(), anyBoolean());
    NameIdentifier tableIdent2 = NameIdentifier.of(tableNs, "table2");
    Table table2 =
        tableOperationDispatcher.createTable(
            tableIdent2, columns, "comment", props, new Transform[0]);

    // Check if the created Schema's field values are correct
    Assertions.assertEquals("table2", table2.name());
    Assertions.assertEquals("comment", table2.comment());
    testProperties(props, table2.properties());

    // Check if the Table entity is stored in the EntityStore
    Assertions.assertFalse(entityStore.exists(tableIdent2, TABLE));
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> entityStore.get(tableIdent2, TABLE, TableEntity.class));

    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", table2.auditInfo().creator());
  }

  @Test
  public void testCreateAndLoadTable() throws IOException {
    Namespace tableNs = Namespace.of(metalake, catalog, "schema51");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    schemaOperationDispatcher.createSchema(NameIdentifier.of(tableNs.levels()), "comment", props);

    NameIdentifier tableIdent1 = NameIdentifier.of(tableNs, "table11");
    Column[] columns =
        new Column[] {
          TestColumn.builder().withName("col1").withType(Types.StringType.get()).build(),
          TestColumn.builder().withName("col2").withType(Types.StringType.get()).build()
        };

    Table table1 =
        tableOperationDispatcher.createTable(
            tableIdent1, columns, "comment", props, new Transform[0]);
    Table loadedTable1 = tableOperationDispatcher.loadTable(tableIdent1);
    Assertions.assertEquals(table1.name(), loadedTable1.name());
    Assertions.assertEquals(table1.comment(), loadedTable1.comment());
    testProperties(table1.properties(), loadedTable1.properties());
    Assertions.assertEquals(0, loadedTable1.partitioning().length);
    Assertions.assertArrayEquals(table1.columns(), loadedTable1.columns());
    // Audit info is gotten from the entity store
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, loadedTable1.auditInfo().creator());

    // Case 2: Test if the table entity is not found in the entity store
    reset(entityStore);
    entityStore.delete(tableIdent1, TABLE);
    entityStore.delete(NameIdentifier.of(tableNs.levels()), SCHEMA);
    doThrow(new NoSuchEntityException(""))
        .when(entityStore)
        .get(any(), eq(Entity.EntityType.TABLE), any());
    Table loadedTable2 = tableOperationDispatcher.loadTable(tableIdent1);
    // Succeed to import the topic entity
    Assertions.assertTrue(entityStore.exists(NameIdentifier.of(tableNs.levels()), SCHEMA));
    Assertions.assertTrue(entityStore.exists(tableIdent1, TABLE));
    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", loadedTable2.auditInfo().creator());

    // Case 3: Test if the entity store is failed to get the table entity
    reset(entityStore);
    entityStore.delete(tableIdent1, TABLE);
    entityStore.delete(NameIdentifier.of(tableNs.levels()), SCHEMA);
    doThrow(new IOException()).when(entityStore).get(any(), eq(Entity.EntityType.TABLE), any());
    Table loadedTable3 = tableOperationDispatcher.loadTable(tableIdent1);
    // Succeed to import the topic entity
    Assertions.assertTrue(entityStore.exists(NameIdentifier.of(tableNs.levels()), SCHEMA));
    Assertions.assertTrue(entityStore.exists(tableIdent1, TABLE));
    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", loadedTable3.auditInfo().creator());

    // Case 4: Test if the table entity is not matched
    reset(entityStore);
    TableEntity tableEntity =
        TableEntity.builder()
            .withId(1L)
            .withName("table11")
            .withNamespace(tableNs)
            .withAuditInfo(
                AuditInfo.builder().withCreator("gravitino").withCreateTime(Instant.now()).build())
            .build();
    doReturn(tableEntity).when(entityStore).get(any(), eq(Entity.EntityType.TABLE), any());
    Table loadedTable4 = tableOperationDispatcher.loadTable(tableIdent1);
    // Succeed to import the topic entity
    reset(entityStore);
    TableEntity tableImportedEntity = entityStore.get(tableIdent1, TABLE, TableEntity.class);
    Assertions.assertEquals("test", tableImportedEntity.auditInfo().creator());
    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", loadedTable4.auditInfo().creator());
  }

  @Test
  public void testCreateAndAlterTable() throws IOException {
    Namespace tableNs = Namespace.of(metalake, catalog, "schema61");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    schemaOperationDispatcher.createSchema(NameIdentifier.of(tableNs.levels()), "comment", props);

    NameIdentifier tableIdent = NameIdentifier.of(tableNs, "table21");
    Column[] columns =
        new Column[] {
          TestColumn.builder().withName("col1").withType(Types.StringType.get()).build(),
          TestColumn.builder().withName("col2").withType(Types.StringType.get()).build()
        };

    Table table =
        tableOperationDispatcher.createTable(
            tableIdent, columns, "comment", props, new Transform[0]);

    // Test immutable table properties
    TableChange[] illegalChange =
        new TableChange[] {TableChange.setProperty(COMMENT_KEY, "new comment")};
    testPropertyException(
        () -> tableOperationDispatcher.alterTable(tableIdent, illegalChange),
        "Property comment is immutable or reserved, cannot be set");

    TableChange[] changes =
        new TableChange[] {TableChange.setProperty("k3", "v3"), TableChange.removeProperty("k1")};

    Table alteredTable = tableOperationDispatcher.alterTable(tableIdent, changes);
    Assertions.assertEquals(table.name(), alteredTable.name());
    Assertions.assertEquals(table.comment(), alteredTable.comment());
    Map<String, String> expectedProps = ImmutableMap.of("k2", "v2", "k3", "v3");
    testProperties(expectedProps, alteredTable.properties());
    // Audit info is gotten from gravitino entity store
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, alteredTable.auditInfo().creator());
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, alteredTable.auditInfo().lastModifier());

    // Case 2: Test if the table entity is not found in the entity store
    reset(entityStore);
    doThrow(new NoSuchEntityException("")).when(entityStore).update(any(), any(), any(), any());
    Table alteredTable2 = tableOperationDispatcher.alterTable(tableIdent, changes);
    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", alteredTable2.auditInfo().creator());
    Assertions.assertEquals("test", alteredTable2.auditInfo().lastModifier());

    // Case 3: Test if the entity store is failed to update the table entity
    reset(entityStore);
    doThrow(new IOException()).when(entityStore).update(any(), any(), any(), any());
    Table alteredTable3 = tableOperationDispatcher.alterTable(tableIdent, changes);
    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", alteredTable3.auditInfo().creator());
    Assertions.assertEquals("test", alteredTable3.auditInfo().lastModifier());

    // Case 4: Test if the table entity is not matched
    reset(entityStore);
    TableEntity unmatchedEntity =
        TableEntity.builder()
            .withId(1L)
            .withName("table21")
            .withNamespace(tableNs)
            .withAuditInfo(
                AuditInfo.builder().withCreator("gravitino").withCreateTime(Instant.now()).build())
            .build();
    doReturn(unmatchedEntity).when(entityStore).update(any(), any(), any(), any());
    Table alteredTable4 = tableOperationDispatcher.alterTable(tableIdent, changes);
    // Audit info is gotten from the catalog, not from the entity store
    Assertions.assertEquals("test", alteredTable4.auditInfo().creator());
    Assertions.assertEquals("test", alteredTable4.auditInfo().lastModifier());
  }

  @Test
  public void testCreateAndDropTable() throws IOException {
    NameIdentifier tableIdent = NameIdentifier.of(metalake, catalog, "schema71", "table31");
    Map<String, String> props = ImmutableMap.of("k1", "v1", "k2", "v2");
    Column[] columns =
        new Column[] {
          TestColumn.builder().withName("col1").withType(Types.StringType.get()).build(),
          TestColumn.builder().withName("col2").withType(Types.StringType.get()).build()
        };
    schemaOperationDispatcher.createSchema(
        NameIdentifier.of(tableIdent.namespace().levels()), "comment", props);

    tableOperationDispatcher.createTable(tableIdent, columns, "comment", props, new Transform[0]);

    boolean dropped = tableOperationDispatcher.dropTable(tableIdent);
    Assertions.assertTrue(dropped);
    Assertions.assertFalse(tableOperationDispatcher.dropTable(tableIdent));

    // Test if the entity store is failed to drop the table entity
    tableOperationDispatcher.createTable(tableIdent, columns, "comment", props, new Transform[0]);
    reset(entityStore);
    doThrow(new IOException()).when(entityStore).delete(any(), any(), anyBoolean());
    Assertions.assertThrows(
        RuntimeException.class, () -> tableOperationDispatcher.dropTable(tableIdent));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSchemaSupplierUsesExplicitDispatcher() throws IllegalAccessException {
    // Verify that the schemaDispatcherSupplier inside DatastratoTableOperationDispatcher
    // resolves to the explicitly wired schema dispatcher, not the OSS default.
    // In production, DatastratoGravitinoEnv.initializeFullComponents() passes
    // () -> datastratoSchemaDispatcher via the 5-arg constructor — this mirrors that pattern.
    Supplier<SchemaDispatcher> supplier =
        (Supplier<SchemaDispatcher>)
            FieldUtils.readField(tableOperationDispatcher, "schemaDispatcherSupplier", true);

    // @BeforeAll wired the supplier as () -> schemaOperationDispatcher
    Assertions.assertNotNull(supplier.get());
    Assertions.assertSame(
        schemaOperationDispatcher,
        supplier.get(),
        "Schema supplier must return the explicitly wired Datastrato schema dispatcher");
    // GravitinoEnv.schemaDispatcher() is null in test context — confirms the supplier is
    // not the OSS default.
    Assertions.assertNotSame(
        GravitinoEnv.getInstance().schemaDispatcher(),
        supplier.get(),
        "Schema supplier must NOT fall back to the OSS GravitinoEnv default");
  }

  @Test
  public void testPreviewTableData() {
    NameIdentifier tableIdent = NameIdentifier.of(metalake, catalog, "schema99", "table84");
    when(trinoJdbcDataPreviewOperator.preview(any(), any(), anyInt(), any()))
        .thenReturn(new Map[0]);
    Map<String, Object>[] results =
        tableOperationDispatcher.preview(tableIdent, TABLE, 100, new Column[0]);
    Assertions.assertEquals(results.length, 0);

    when(trinoJdbcDataPreviewOperator.preview(any(), any(), anyInt(), any()))
        .thenThrow(new RuntimeException("mock error"));
    Assertions.assertThrows(
        RuntimeException.class,
        () -> tableOperationDispatcher.preview(tableIdent, TABLE, 100, new Column[0]));
  }
}
