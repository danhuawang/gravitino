/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static org.apache.gravitino.Entity.EntityType.CATALOG;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.SCHEMA;

import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.InMemorySearchStorage;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestSearchService {
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;
  private InMemorySearchStorage inMemorySearchStorage;

  @BeforeAll
  public void initTest() throws IllegalAccessException {
    this.gravitinoService = new MockedGravitinoService();

    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL)).thenReturn("memory");
    Mockito.when(config.getAllConfig())
        .thenReturn(ImmutableMap.of(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL.getKey(), "memory"));
    SearchService service = new SearchService(config);
    this.searchService = gravitinoService.createMokedSearchService(service);
    this.inMemorySearchStorage = (InMemorySearchStorage) searchService.storage;

    /**
     * Prepare the data of mocked gravitino service. Entities are organized in a tree structure. The
     * root is the metalake, see as the example below:
     *
     * <pre>
     *   test_metalake
     *    ├── test_catalog1
     *    │   ├── test_schema1
     *    │   │   ├── test_table1
     *    │   │   └── test_table2
     *    │   └── test_schema2
     *    │       └── test_table2
     *    |── test_catalog2
     *    │   ├── test_schema1
     *    │   └── test_schema2
     * </pre>
     */
    gravitinoService.createMetalake("test_metalake");
    gravitinoService.createCatalog(NameIdentifier.of("test_metalake", "test_catalog1"));
    gravitinoService.createCatalog(NameIdentifier.of("test_metalake", "test_catalog2"));
    gravitinoService.createSchema(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1"));
    gravitinoService.createSchema(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2"));
    gravitinoService.createSchema(
        NameIdentifier.of("test_metalake", "test_catalog2", "test_schema1"));
    gravitinoService.createSchema(
        NameIdentifier.of("test_metalake", "test_catalog2", "test_schema2"));
    gravitinoService.createTable(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table1"));
    gravitinoService.createTable(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table2"));
    gravitinoService.createTable(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2", "test_table2"));
  }

  @Test
  void testSynchronizeMetadata() throws Exception {
    try {
      // Synchronize all metadata
      NameIdentifier identifier = NameIdentifier.of("test_metalake");
      testSyncTask(identifier, METALAKE, true, 9);

      // Sync a non-existing metalake
      Assertions.assertThrows(
          NoSuchMetalakeException.class,
          () -> {
            NameIdentifier noExsitIdentifier = NameIdentifier.of("test_metalake1");
            testSyncTask(noExsitIdentifier, METALAKE, true, 0);
          });

      // Only sync test_metalake.test_catalog1 itself
      identifier = NameIdentifier.of("test_metalake", "test_catalog1");
      testSyncTask(identifier, CATALOG, false, 1);

      // Sync test_metalake.test_catalog1 and all its children
      identifier = NameIdentifier.of("test_metalake", "test_catalog1");
      testSyncTask(identifier, CATALOG, true, 6);

      // Only sync test_metalake.test_catalog2 itself
      identifier = NameIdentifier.of("test_metalake", "test_catalog2");
      testSyncTask(identifier, CATALOG, false, 1);

      // Sync test_metalake.test_catalog2 and all its children
      identifier = NameIdentifier.of("test_metalake", "test_catalog2");
      testSyncTask(identifier, CATALOG, true, 3);

      // Only sync test_metalake.test_catalog1.test_schema1 itself
      identifier = NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1");
      testSyncTask(identifier, SCHEMA, false, 1);

      // Sync test_metalake.test_catalog1.test_schema1 and all its children
      identifier = NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1");
      testSyncTask(identifier, SCHEMA, true, 3);

      // Only sync test_metalake.test_catalog1.test_schema2 itself
      identifier = NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2");
      testSyncTask(identifier, SCHEMA, false, 1);

      // Sync test_metalake.test_catalog1.test_schema2 and all its children
      identifier = NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2");
      testSyncTask(identifier, SCHEMA, true, 2);

      // Only sync test_metalake.test_catalog2.test_schema1 itself
      identifier = NameIdentifier.of("test_metalake", "test_catalog2", "test_schema1");
      testSyncTask(identifier, SCHEMA, false, 1);

      // Sync test_metalake.test_catalog2.test_schema1 and all its children
      identifier = NameIdentifier.of("test_metalake", "test_catalog2", "test_schema1");
      testSyncTask(identifier, SCHEMA, true, 1);

      // Only sync test_metalake.test_catalog2.test_schema2 itself
      identifier = NameIdentifier.of("test_metalake", "test_catalog2", "test_schema2");
      testSyncTask(identifier, SCHEMA, false, 1);

      // Sync test_metalake.test_catalog2.test_schema2 and all its children
      identifier = NameIdentifier.of("test_metalake", "test_catalog2", "test_schema2");
      testSyncTask(identifier, SCHEMA, true, 1);

      // Sync a non-existing schema
      identifier = NameIdentifier.of("test_metalake", "test_catalog1", "test_schema3");
      testSyncTask(identifier, SCHEMA, true, 0);

      // Only sync test_metalake.test_catalog1.test_schema1.test_table1 itself
      identifier =
          NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table1");
      testSyncTask(identifier, Entity.EntityType.TABLE, false, 1);

      // Sync test_metalake.test_catalog1.test_schema1.test_table1 and all its children
      identifier =
          NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table1");
      testSyncTask(identifier, Entity.EntityType.TABLE, true, 1);

      // Only sync test_metalake.test_catalog1.test_schema1.test_table2 itself
      identifier =
          NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table2");
      testSyncTask(identifier, Entity.EntityType.TABLE, false, 1);

      // Sync test_metalake.test_catalog1.test_schema1.test_table2 and all its children
      identifier =
          NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table2");
      testSyncTask(identifier, Entity.EntityType.TABLE, true, 1);

      // Sync test_metalake.test_catalog1.test_schema2.test_table3, this table does not exist.
      identifier =
          NameIdentifier.of("test_metalake", "test_catalog1", "test_schema2", "test_table3");
      testSyncTask(identifier, Entity.EntityType.TABLE, true, 0);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  void testSyncTask(
      NameIdentifier nameIdentifier, Entity.EntityType type, boolean cascading, int expectedCount)
      throws Exception {
    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    MetadataObject testObj = NameIdentifierUtil.toMetadataObject(nameIdentifier, type);

    SyncTask task = searchService.synchronizeMetadata(metalake, testObj, cascading);
    TaskStatusDTO taskStatus = searchService.getTaskStatus(task.getTaskId());
    Assertions.assertNotNull(taskStatus);
    Assertions.assertEquals(task.getTaskId(), taskStatus.getTaskId());

    task.waitToFinished();

    Thread.sleep(10);
    taskStatus = searchService.getTaskStatus(task.getTaskId());
    Assertions.assertNotNull(taskStatus);
    Assertions.assertEquals(task.getTaskId(), taskStatus.getTaskId());
    Assertions.assertEquals(TaskStatus.TaskStatusEnum.COMPLETED.name(), taskStatus.getTaskStatus());

    List<SearchEntityPO> searchEntityList = inMemorySearchStorage.getSearchEntities();
    Assertions.assertEquals(expectedCount, searchEntityList.size());
    searchEntityList.forEach(
        searchEntityPO -> {
          Assertions.assertEquals(
              gravitinoService.getEntityId(getIdentifier(searchEntityPO)),
              searchEntityPO.getEntityId());
          Assertions.assertEquals(
              NameIdentifierUtil.getMetalake(nameIdentifier), searchEntityPO.getMetalake());
          if (!cascading) {
            Assertions.assertEquals(nameIdentifier.name(), searchEntityPO.getEntityName());
          } else {
            String fullName = type != METALAKE ? testObj.fullName() : "";
            Assertions.assertTrue(searchEntityPO.getFullQualifiedName().startsWith(fullName));
          }
        });
    searchEntityList.clear();
  }

  protected NameIdentifier getIdentifier(SearchEntityPO searchEntityPO) {
    return NameIdentifier.parse(
        searchEntityPO.getMetalake() + "." + searchEntityPO.getFullQualifiedName());
  }
}
