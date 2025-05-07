/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;

import com.datastrato.gravitino.TestCatalog;
import com.datastrato.gravitino.TestFileset;
import com.datastrato.gravitino.TestSchema;
import com.datastrato.gravitino.TestTable;
import com.datastrato.gravitino.TestTopic;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.InMemorySearchStorage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog.Type;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.EntityCombinedFileset;
import org.apache.gravitino.catalog.EntityCombinedSchema;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.catalog.EntityCombinedTopic;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.ColumnEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestSearchService {

  /**
   * Entities are organized as the following tree structure. The root is the metalake.
   *
   * <pre>
   *   test_metalake                            ID: 1
   *    ├── test_catalog1                       ID: 2
   *    │   ├── test_schema1                    ID:4
   *    │   │   ├── test_table1                 ID:8
   *    │   │   └── test_table2                 ID:9
   *    │   └── test_schema2                    ID:5
   *    │       └── test_table2                 ID:10
   *    |── test_catalog2                       ID:3
   *    │   ├── test_schema1                    ID:6
   *    │   └── test_schema2                    ID:7
   * </pre>
   */
  private SearchService prepareSearchService(SearchService service) throws IllegalAccessException {
    SearchService spyService = Mockito.spy(service);
    MetalakeDispatcher metalakeDispatcher = Mockito.mock(MetalakeDispatcher.class);
    BaseMetalake baseMetalake =
        BaseMetalake.builder()
            .withName("test_metalake")
            .withVersion(SchemaVersion.V_0_1)
            .withAuditInfo(AuditInfo.EMPTY)
            .withId(1L)
            .build();

    Mockito.when(metalakeDispatcher.listMetalakes()).thenReturn(new Metalake[] {baseMetalake});
    Mockito.when(metalakeDispatcher.loadMetalake(Mockito.eq(NameIdentifier.of("test_metalake"))))
        .thenReturn(baseMetalake);
    Mockito.when(metalakeDispatcher.loadMetalake(Mockito.eq(NameIdentifier.of("test_metalake1"))))
        .thenThrow(new NoSuchMetalakeException("test_metalake1"));
    Mockito.when(metalakeDispatcher.metalakeExists(Mockito.eq(NameIdentifier.of("test_metalake"))))
        .thenReturn(true);

    CatalogDispatcher catalogDispatcher = Mockito.mock(CatalogDispatcher.class);
    Mockito.when(catalogDispatcher.listCatalogs(Namespace.of("test_metalake")))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog1"),
              NameIdentifier.of("test_metalake", "test_catalog2"),
            });

    Mockito.when(
            catalogDispatcher.catalogExists(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog1"))))
        .thenReturn(true);
    Mockito.when(
            catalogDispatcher.catalogExists(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog2"))))
        .thenReturn(true);

    BaseCatalog baseCatalog1 = new TestCatalog();
    FieldUtils.writeField(
        baseCatalog1,
        "entity",
        CatalogEntity.builder()
            .withName("test_catalog1")
            .withAuditInfo(AuditInfo.EMPTY)
            .withId(2L)
            .withProvider("jdbc-mysql")
            .withType(Type.RELATIONAL)
            .withProperties(ImmutableMap.of())
            .build(),
        true);

    BaseCatalog baseCatalog2 = new TestCatalog();
    FieldUtils.writeField(
        baseCatalog2,
        "entity",
        CatalogEntity.builder()
            .withName("test_catalog2")
            .withAuditInfo(AuditInfo.EMPTY)
            .withId(3L)
            .withProvider("jdbc-mysql")
            .withType(Type.RELATIONAL)
            .withProperties(ImmutableMap.of())
            .build(),
        true);

    Mockito.when(
            catalogDispatcher.loadCatalog(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog2"))))
        .thenReturn(baseCatalog2);
    Mockito.when(
            catalogDispatcher.loadCatalog(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog1"))))
        .thenReturn(baseCatalog1);

    // Catalog1 contains schema1, schema2
    // Catalog2 contains schema1, schema2
    SchemaDispatcher schemaDispatcher = Mockito.mock(SchemaDispatcher.class);
    Mockito.when(
            schemaDispatcher.listSchemas(
                Mockito.eq(Namespace.of("test_metalake", "test_catalog1"))))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1"),
              NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2")
            });
    Mockito.when(
            schemaDispatcher.listSchemas(
                Mockito.eq(Namespace.of("test_metalake", "test_catalog2"))))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog2", "test_schema1"),
              NameIdentifier.of("test_metalake", "test_catalog2", "test_schema2")
            });

    Mockito.when(
            schemaDispatcher.loadSchema(
                NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1")))
        .thenReturn(
            EntityCombinedSchema.of(
                TestSchema.builder()
                    .withName("test_schema1")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog1.test_schema1")
                    .build(),
                SchemaEntity.builder()
                    .withName("test_schema1")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withId(4L)
                    .withComment("test_metalake.test_catalog1.test_schema1")
                    .withProperties(ImmutableMap.of())
                    .build()));

    Mockito.when(
            schemaDispatcher.loadSchema(
                NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2")))
        .thenReturn(
            EntityCombinedSchema.of(
                TestSchema.builder()
                    .withName("test_schema2")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog1.test_schema2")
                    .build(),
                SchemaEntity.builder()
                    .withName("test_schema2")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withComment("test_metalake.test_catalog1.test_schema2")
                    .withId(5L)
                    .withProperties(ImmutableMap.of())
                    .build()));

    Mockito.when(
            schemaDispatcher.loadSchema(
                NameIdentifier.of("test_metalake", "test_catalog2", "test_schema1")))
        .thenReturn(
            EntityCombinedSchema.of(
                TestSchema.builder()
                    .withName("test_schema1")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog2.test_schema1")
                    .build(),
                SchemaEntity.builder()
                    .withName("test_schema1")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withComment("test_metalake.test_catalog2.test_schema1")
                    .withId(6L)
                    .withProperties(ImmutableMap.of())
                    .build()));

    Mockito.when(
            schemaDispatcher.loadSchema(
                NameIdentifier.of("test_metalake", "test_catalog2", "test_schema2")))
        .thenReturn(
            EntityCombinedSchema.of(
                TestSchema.builder()
                    .withName("test_schema2")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog2.test_schema2")
                    .build(),
                SchemaEntity.builder()
                    .withName("test_schema2")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withComment("test_metalake.test_catalog2.test_schema2")
                    .withId(7L)
                    .withProperties(ImmutableMap.of())
                    .build()));
    Mockito.when(
            schemaDispatcher.loadSchema(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog1", "test_schema3"))))
        .thenThrow(new NoSuchSchemaException("Mocked no such schema exception"));

    Mockito.when(
            schemaDispatcher.schemaExists(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1"))))
        .thenReturn(true);

    Mockito.when(
            schemaDispatcher.schemaExists(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2"))))
        .thenReturn(true);
    Mockito.when(
            schemaDispatcher.schemaExists(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog2", "test_schema1"))))
        .thenReturn(true);
    Mockito.when(
            schemaDispatcher.schemaExists(
                Mockito.eq(NameIdentifier.of("test_metalake", "test_catalog2", "test_schema2"))))
        .thenReturn(true);

    // catalog1.schema1 contains table1, table2
    TableDispatcher tableDispatcher = Mockito.mock(TableDispatcher.class);
    Mockito.when(
            tableDispatcher.listTables(
                Namespace.of("test_metalake", "test_catalog1", "test_schema1")))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table1"),
              NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table2")
            });

    Mockito.when(
            tableDispatcher.loadTable(
                Mockito.eq(
                    NameIdentifier.of(
                        "test_metalake", "test_catalog1", "test_schema1", "test_table1"))))
        .thenReturn(
            EntityCombinedTable.of(
                TestTable.builder()
                    .withName("test_table1")
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog1.test_schema1.test_table1")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withColumns(
                        new Column[] {
                          ColumnDTO.builder()
                              .withName("test_column")
                              .withDataType(Types.IntegerType.get())
                              .build()
                        })
                    .build(),
                TableEntity.builder()
                    .withName("test_table1")
                    .withId(8L)
                    .withColumns(
                        Lists.newArrayList(
                            ColumnEntity.builder()
                                .withName("test_column")
                                .withDataType(Types.IntegerType.get())
                                .withAuditInfo(AuditInfo.EMPTY)
                                .withId(10000L)
                                .withPosition(0)
                                .build()))
                    .withAuditInfo(AuditInfo.EMPTY)
                    .build()));

    Mockito.when(
            tableDispatcher.loadTable(
                Mockito.eq(
                    NameIdentifier.of(
                        "test_metalake", "test_catalog1", "test_schema1", "test_table2"))))
        .thenReturn(
            EntityCombinedTable.of(
                TestTable.builder()
                    .withName("test_table2")
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog1.test_schema1.test_table2")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withColumns(
                        new Column[] {
                          ColumnDTO.builder()
                              .withName("test_column")
                              .withDataType(Types.IntegerType.get())
                              .build()
                        })
                    .build(),
                TableEntity.builder()
                    .withName("test_table2")
                    .withId(9L)
                    .withColumns(
                        Lists.newArrayList(
                            ColumnEntity.builder()
                                .withName("test_column")
                                .withDataType(Types.IntegerType.get())
                                .withAuditInfo(AuditInfo.EMPTY)
                                .withId(10001L)
                                .withPosition(0)
                                .build()))
                    .withAuditInfo(AuditInfo.EMPTY)
                    .build()));

    // catalog1.schema2 contains table2
    Mockito.when(
            tableDispatcher.listTables(
                Namespace.of("test_metalake", "test_catalog1", "test_schema2")))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2", "test_table2")
            });
    Mockito.when(
            tableDispatcher.loadTable(
                Mockito.eq(
                    NameIdentifier.of(
                        "test_metalake", "test_catalog1", "test_schema2", "test_table2"))))
        .thenReturn(
            EntityCombinedTable.of(
                TestTable.builder()
                    .withName("test_table2")
                    .withProperties(ImmutableMap.of())
                    .withComment("test_metalake.test_catalog1.test_schema2.test_table2")
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withColumns(
                        new Column[] {
                          ColumnDTO.builder()
                              .withName("test_column")
                              .withDataType(Types.IntegerType.get())
                              .build()
                        })
                    .build(),
                TableEntity.builder()
                    .withName("test_table2")
                    .withId(10L)
                    .withColumns(
                        Lists.newArrayList(
                            ColumnEntity.builder()
                                .withName("test_column")
                                .withDataType(Types.IntegerType.get())
                                .withAuditInfo(AuditInfo.EMPTY)
                                .withId(10002L)
                                .withPosition(0)
                                .build()))
                    .withAuditInfo(AuditInfo.EMPTY)
                    .build()));

    Mockito.when(
            tableDispatcher.loadTable(
                NameIdentifier.of(
                    "test_metalake",
                    "test_catalog1",
                    "test_schema2",
                    "test_table3"))) // catalog2.schema1 contains no tables
        .thenThrow(new NoSuchTableException("Mocked no such table exception"));

    // catalog2.schema1 contains no tables
    Mockito.when(
            tableDispatcher.listTables(
                Namespace.of("test_metalake", "test_catalog2", "test_schema1")))
        .thenReturn(new NameIdentifier[] {});
    // catalog2.schema2 contains no tables
    Mockito.when(
            tableDispatcher.listTables(
                Namespace.of("test_metalake", "test_catalog2", "test_schema2")))
        .thenReturn(new NameIdentifier[] {});

    TopicDispatcher topicDispatcher = Mockito.mock(TopicDispatcher.class);
    Mockito.when(topicDispatcher.listTopics(Mockito.any()))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog", "test_schema", "test_topic")
            });
    Mockito.when(topicDispatcher.loadTopic(Mockito.any()))
        .thenReturn(
            EntityCombinedTopic.of(
                TestTopic.builder()
                    .withName("test_topic")
                    .withProperties(ImmutableMap.of())
                    .withAuditInfo(AuditInfo.EMPTY)
                    .build(),
                TopicEntity.builder()
                    .withName("test_topic")
                    .withId(4L)
                    .withAuditInfo(AuditInfo.EMPTY)
                    .build()));

    FilesetDispatcher filesetDispatcher = Mockito.mock(FilesetDispatcher.class);
    Mockito.when(filesetDispatcher.listFilesets(Mockito.any()))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of("test_metalake", "test_catalog", "test_schema", "test_fileset")
            });
    Mockito.when(filesetDispatcher.loadFileset(Mockito.any()))
        .thenReturn(
            EntityCombinedFileset.of(
                TestFileset.builder()
                    .withName("test_fileset")
                    .withProperties(ImmutableMap.of())
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withType(Fileset.Type.MANAGED)
                    .withStorageLocations(ImmutableMap.of("unknown", "/tmp/test_storage_location"))
                    .build(),
                FilesetEntity.builder()
                    .withName("test_fileset")
                    .withFilesetType(Fileset.Type.MANAGED)
                    .withId(5L)
                    .withAuditInfo(AuditInfo.EMPTY)
                    .withStorageLocations(ImmutableMap.of("unknown", "/tmp/test_storage_location"))
                    .build()));

    TagDispatcher tagDispatcher = Mockito.mock(TagDispatcher.class);
    Mockito.when(tagDispatcher.listTagsInfoForMetadataObject(Mockito.any(), Mockito.any()))
        .thenReturn(
            new Tag[] {
              TagDTO.builder()
                  .withName("test_tag")
                  .withComment("test_tag_comment")
                  .withAudit(AuditDTO.builder().build())
                  .withProperties(ImmutableMap.of("key", "value"))
                  .build()
            });

    GravitinoEnv gravitinoEnv = GravitinoEnv.getInstance();
    FieldUtils.writeField(gravitinoEnv, "metalakeDispatcher", metalakeDispatcher, true);
    FieldUtils.writeField(gravitinoEnv, "catalogDispatcher", catalogDispatcher, true);
    FieldUtils.writeField(gravitinoEnv, "schemaDispatcher", schemaDispatcher, true);
    FieldUtils.writeField(gravitinoEnv, "tableDispatcher", tableDispatcher, true);
    FieldUtils.writeField(gravitinoEnv, "tagDispatcher", tagDispatcher, true);

    return spyService;
  }

  /**
   * Entities are organized in a tree structure. The root is the metalake, see as the example below:
   *
   * <pre>
   *   test_metalake                            ID: 1
   *    ├── test_catalog1                       ID: 2
   *    │   ├── test_schema1                    ID:4
   *    │   │   ├── test_table1                 ID:8
   *    │   │   └── test_table2                 ID:9
   *    │   └── test_schema2                    ID:5
   *    │       └── test_table2                 ID:10
   *    |── test_catalog2                       ID:3
   *    │   ├── test_schema1                    ID:6
   *    │   └── test_schema2                    ID:7
   * </pre>
   */
  @Test
  void testSynchronizeMetadata()
      throws IllegalAccessException, InterruptedException, ExecutionException {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL)).thenReturn("memory");
    Mockito.when(config.getAllConfig())
        .thenReturn(ImmutableMap.of(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL.getKey(), "memory"));
    SearchService service = new SearchService(config);
    SearchService spyService = prepareSearchService(service);

    try {
      InMemorySearchStorage inMemorySearchStorage = (InMemorySearchStorage) spyService.storage;
      List<SearchEntityPO> searchEntityList = inMemorySearchStorage.getSearchEntities();
      SyncTask task;

      // Synchronize all metadata
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_metalake", MetadataObject.Type.METALAKE),
              true);
      task.waitToFinished();
      Assertions.assertEquals(9, searchEntityList.size());
      Set<Long> ids =
          searchEntityList.stream().map(SearchEntityPO::getEntityId).collect(Collectors.toSet());
      ids.containsAll(
          ImmutableList.of(
              2L, // test_catalog1
              3L, // test_catalog2
              4L, // test_schema1
              5L, // test_schema2
              6L, // test_schema1
              7L, // test_schema2
              8L, // test_table1
              9L, // test_table2
              10L // test_table2
              ));
      inMemorySearchStorage.clear();

      // Sync a non-existing metalake
      Assertions.assertThrows(
          NoSuchMetalakeException.class,
          () -> {
            spyService.synchronizeMetadata(
                "test_metalake1",
                MetadataObjects.parse("test_metalake", MetadataObject.Type.METALAKE),
                true);
          });

      // Only sync test_metalake.test_catalog1 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1", MetadataObject.Type.CATALOG),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_catalog1", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(2L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog1 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1", MetadataObject.Type.CATALOG),
              true);
      task.waitToFinished();
      Assertions.assertEquals(6, searchEntityList.size());
      Set<Long> ids2 =
          searchEntityList.stream().map(SearchEntityPO::getEntityId).collect(Collectors.toSet());
      ids2.containsAll(
          ImmutableList.of(
              2L, // test_catalog1
              4L, // test_schema1
              5L, // test_schema2
              8L, // test_table1
              9L, // test_table2
              10L // test_table2
              ));
      inMemorySearchStorage.clear();

      // Only sync test_metalake.test_catalog2 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog2", MetadataObject.Type.CATALOG),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_catalog2", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(3L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog2 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog2", MetadataObject.Type.CATALOG),
              true);
      task.waitToFinished();
      Assertions.assertEquals(3, searchEntityList.size());
      Set<Long> ids3 =
          searchEntityList.stream().map(SearchEntityPO::getEntityId).collect(Collectors.toSet());
      ids3.containsAll(
          ImmutableList.of(
              3L, // test_catalog2
              6L, // test_schema1
              7L // test_schema2
              ));
      inMemorySearchStorage.clear();

      // Only sync test_metalake.test_catalog1.test_schema1 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1.test_schema1", MetadataObject.Type.SCHEMA),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_schema1", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(4L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog1.test_schema1 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1.test_schema1", MetadataObject.Type.SCHEMA),
              true);
      task.waitToFinished();
      Assertions.assertEquals(3, searchEntityList.size());
      Set<Long> ids4 =
          searchEntityList.stream().map(SearchEntityPO::getEntityId).collect(Collectors.toSet());
      ids4.containsAll(
          ImmutableList.of(
              4L, // test_schema1
              8L, // test_table1
              9L // test_table2
              ));
      inMemorySearchStorage.clear();

      // Only sync test_metalake.test_catalog1.test_schema2 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1.test_schema2", MetadataObject.Type.SCHEMA),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_schema2", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(5L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog1.test_schema2 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1.test_schema2", MetadataObject.Type.SCHEMA),
              true);
      task.waitToFinished();
      Assertions.assertEquals(2, searchEntityList.size());
      Set<Long> ids5 =
          searchEntityList.stream().map(SearchEntityPO::getEntityId).collect(Collectors.toSet());
      ids5.containsAll(
          ImmutableList.of(
              5L, // test_schema2
              10L // test_table2
              ));
      inMemorySearchStorage.clear();

      // Only sync test_metalake.test_catalog2.test_schema1 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog2.test_schema1", MetadataObject.Type.SCHEMA),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_schema1", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(6L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog2.test_schema1 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog2.test_schema1", MetadataObject.Type.SCHEMA),
              true);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_schema1", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(6L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Only sync test_metalake.test_catalog2.test_schema2 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog2.test_schema2", MetadataObject.Type.SCHEMA),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_schema2", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(7L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog2.test_schema2 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog2.test_schema2", MetadataObject.Type.SCHEMA),
              true);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals("test_schema2", searchEntityList.get(0).getEntityName());
      Assertions.assertEquals(7L, searchEntityList.get(0).getEntityId());
      inMemorySearchStorage.clear();

      // Sync a non-existing schema
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse("test_catalog1.test_schema3", MetadataObject.Type.SCHEMA),
              true);
      task.waitToFinished();
      Assertions.assertEquals(0, searchEntityList.size());

      // Only sync test_metalake.test_catalog1.test_schema1.test_table1 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse(
                  "test_catalog1.test_schema1.test_table1", MetadataObject.Type.TABLE),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals(8L, searchEntityList.get(0).getEntityId());
      Assertions.assertEquals("test_table1", searchEntityList.get(0).getEntityName());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog1.test_schema1.test_table1 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse(
                  "test_catalog1.test_schema1.test_table1", MetadataObject.Type.TABLE),
              true);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals(8L, searchEntityList.get(0).getEntityId());
      Assertions.assertEquals("test_table1", searchEntityList.get(0).getEntityName());
      inMemorySearchStorage.clear();

      // Only sync test_metalake.test_catalog1.test_schema1.test_table2 itself
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse(
                  "test_catalog1.test_schema1.test_table2", MetadataObject.Type.TABLE),
              false);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals(9L, searchEntityList.get(0).getEntityId());
      Assertions.assertEquals("test_table2", searchEntityList.get(0).getEntityName());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog1.test_schema1.test_table2 and all its children
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse(
                  "test_catalog1.test_schema1.test_table2", MetadataObject.Type.TABLE),
              true);
      task.waitToFinished();
      Assertions.assertEquals(1, searchEntityList.size());
      Assertions.assertEquals(9L, searchEntityList.get(0).getEntityId());
      Assertions.assertEquals("test_table2", searchEntityList.get(0).getEntityName());
      inMemorySearchStorage.clear();

      // Sync test_metalake.test_catalog1.test_schema2.test_table3, this table does not exist.
      task =
          spyService.synchronizeMetadata(
              "test_metalake",
              MetadataObjects.parse(
                  "test_catalog1.test_schema2.test_table3", MetadataObject.Type.TABLE),
              true);
      task.waitToFinished();
      Assertions.assertEquals(0, searchEntityList.size());

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
