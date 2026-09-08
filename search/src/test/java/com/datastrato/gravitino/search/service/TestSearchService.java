/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE;
import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.search.utils.FilterConditionUtils.createEntityNameQueryCondition;
import static java.util.Collections.emptyList;
import static org.apache.gravitino.Entity.EntityType.CATALOG;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.POLICY;
import static org.apache.gravitino.Entity.EntityType.SCHEMA;
import static org.apache.gravitino.Entity.EntityType.TABLE;
import static org.apache.gravitino.Entity.EntityType.TAG;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.InMemorySearchStorage;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.datastrato.gravitino.search.store.WriteContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.EntityCombinedSchema;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.awaitility.Awaitility;
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

    SearchService service = newMemorySearchService();
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
     *    │   │   ├── test_table1 (test_tag)
     *    │   │   └── test_table2
     *    │   └── test_schema2
     *    │       └── test_table2
     *    |── test_catalog2 (test_tag)
     *    │   ├── test_schema1
     *    │   └── test_schema2
     *    |── test_catalog3
     * </pre>
     */
    gravitinoService.createMetalake("test_metalake");
    gravitinoService.createCatalog(NameIdentifier.of("test_metalake", "test_catalog1"));
    gravitinoService.createCatalog(NameIdentifier.of("test_metalake", "test_catalog2"));
    gravitinoService.createCatalog(NameIdentifier.of("test_metalake", "test_catalog3"));
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

    gravitinoService.addTagsToObject(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1", "test_table1"),
        ImmutableSet.of("test_tag"));

    gravitinoService.addTagsToObject(
        NameIdentifier.of("test_metalake", "test_catalog2"), ImmutableSet.of("test_tag"));

    gravitinoService.createPolicy("test_policy");
    gravitinoService.addPoliciesToObject(
        NameIdentifier.of("test_metalake", "test_catalog1", "test_schema1"),
        ImmutableSet.of("test_policy"));
  }

  @Test
  void testSynchronizeMetadata() throws Exception {
    try {
      // Synchronize all metadata
      NameIdentifier identifier = NameIdentifier.of("test_metalake");
      testSyncTask(identifier, METALAKE, true, 12);

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

  @Test
  void testQuery() throws Exception {
    String metalake = "test_metalake";
    NameIdentifier nameIdentifier = NameIdentifier.of(metalake);
    MetadataObject testObj = NameIdentifierUtil.toMetadataObject(nameIdentifier, METALAKE);
    SyncTask task = searchService.synchronizeMetadata(metalake, testObj, true);
    task.waitToFinished();

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> searchService.query(metalake, null, -1, 10));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> searchService.query(metalake, null, 0, 0));
    Assertions.assertThrows(
        NoSuchMetalakeException.class, () -> searchService.query("missing_metalake", null, 0, 10));

    // test query catalog
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1");
    List<SearchEntitiesDTO> dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, false),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, CATALOG).getEntities().size());

    // test query schema
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1", "test_schema1");
    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, false),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, SCHEMA).getEntities().size());

    // test query table
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1", "test_schema1", "test_table1");
    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, false),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, TABLE).getEntities().size());

    // query metalake
    dto = searchService.query(metalake, null, null, emptyList(), 0, Integer.MAX_VALUE);
    Assertions.assertEquals(3, getSearchEntitiesDTOByType(dto, CATALOG).getEntities().size());
    Assertions.assertEquals(4, getSearchEntitiesDTOByType(dto, SCHEMA).getEntities().size());
    Assertions.assertEquals(3, getSearchEntitiesDTOByType(dto, TABLE).getEntities().size());
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, TAG).getEntities().size());
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, POLICY).getEntities().size());

    dto = searchService.query(metalake, "test_policy", 0, Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, POLICY).getEntities().size());
    Assertions.assertEquals(2, getSearchEntitiesDTOByType(dto, TABLE).getEntities().size());
    Assertions.assertTrue(
        getSearchEntitiesDTOByType(dto, TABLE).getEntities().stream()
            .allMatch(table -> table.getPolicyNames().contains("test_policy")));

    dto = searchService.query(metalake, "retentionDays", 0, Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, POLICY).getEntities().size());

    // test query catalog with cascading
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1");
    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, true),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, CATALOG).getEntities().size());
    Assertions.assertEquals(2, getSearchEntitiesDTOByType(dto, SCHEMA).getEntities().size());
    Assertions.assertEquals(3, getSearchEntitiesDTOByType(dto, TABLE).getEntities().size());

    // test query schema with cascading
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1", "test_schema1");
    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, true),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, SCHEMA).getEntities().size());
    Assertions.assertEquals(2, getSearchEntitiesDTOByType(dto, TABLE).getEntities().size());

    // test query by tag
    dto =
        searchService.query(
            metalake,
            null,
            new Condition.InCondition("tag_name", ImmutableList.of("test_tag")),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(1, getSearchEntitiesDTOByType(dto, TABLE).getEntities().size());

    // test remove table
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1", "test_schema1", "test_table1");
    searchService.removeMetadata(nameIdentifier, Entity.EntityType.TABLE, false).get();
    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, false),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(0, dto.size());

    // test remove test_schema1 with cascade
    nameIdentifier = NameIdentifier.of(metalake, "test_catalog1", "test_schema1");
    searchService.removeMetadata(nameIdentifier, Entity.EntityType.SCHEMA, true).get();
    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, false),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(0, dto.size());

    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(nameIdentifier, true),
            emptyList(),
            0,
            Integer.MAX_VALUE);
    Assertions.assertEquals(0, dto.size());

    dto =
        searchService.query(
            metalake,
            null,
            createEntityNameQueryCondition(NameIdentifier.of(metalake, "test_catalog1"), true),
            emptyList(),
            0,
            Integer.MAX_VALUE);

    Assertions.assertEquals(3, dto.size());
    SearchEntitiesDTO catalogDTOs = getSearchEntitiesDTOByType(dto, CATALOG);
    Assertions.assertNotNull(catalogDTOs);
    Assertions.assertEquals(1, catalogDTOs.getEntities().size());
    Assertions.assertEquals(
        "test_catalog1", catalogDTOs.getEntities().get(0).getFullQualifiedName());

    SearchEntitiesDTO schemaDTOs = getSearchEntitiesDTOByType(dto, SCHEMA);
    Assertions.assertNotNull(schemaDTOs);
    Assertions.assertEquals(1, schemaDTOs.getEntities().size());
    Assertions.assertEquals(
        "test_catalog1.test_schema2", schemaDTOs.getEntities().get(0).getFullQualifiedName());

    SearchEntitiesDTO tableDTOs = getSearchEntitiesDTOByType(dto, TABLE);
    Assertions.assertNotNull(tableDTOs);
    Assertions.assertEquals(1, tableDTOs.getEntities().size());
    Assertions.assertEquals(
        "test_catalog1.test_schema2.test_table2",
        tableDTOs.getEntities().get(0).getFullQualifiedName());
  }

  @Test
  void testTransactionalWriteRejectsMultipleMetalakes() {
    SearchEntityPO firstEntity =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(1001L)
            .withEntityType(Entity.EntityType.SCHEMA)
            .withMetalake("metalake_a")
            .withEntityName("schema_a")
            .withCatalogName("catalog")
            .withFullQualifiedName("catalog.schema_a")
            .build();
    SearchEntityPO secondEntity =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(1002L)
            .withEntityType(Entity.EntityType.SCHEMA)
            .withMetalake("metalake_b")
            .withEntityName("schema_b")
            .withCatalogName("catalog")
            .withFullQualifiedName("catalog.schema_b")
            .build();
    WriteContext context = WriteContext.builder().withTransactionId(1L).build();

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> searchService.write(ImmutableList.of(firstEntity, secondEntity), context));

    Assertions.assertEquals(
        "Transactional writes must contain entities from exactly one metalake",
        exception.getMessage());
  }

  @Test
  void testMetalakeInUseUpdateFutureReportsStorageFailure() throws Exception {
    SearchStorage failingStorage = Mockito.mock(SearchStorage.class);
    Mockito.doThrow(new RuntimeException("storage failure"))
        .when(failingStorage)
        .updateMetalakeInUse("failing_metalake", false);

    try (SearchService failingService = newSearchServiceWithStorage(failingStorage)) {
      Future<?> update =
          failingService.updateMetalakeInUse(NameIdentifier.of("failing_metalake"), false);
      ExecutionException exception =
          Assertions.assertThrows(ExecutionException.class, () -> update.get(10, TimeUnit.SECONDS));

      Assertions.assertTrue(
          exception
              .getCause()
              .getMessage()
              .contains("Failed to update the in-use state for metalake failing_metalake"));
    }
  }

  @Test
  void testMetalakeDeletionFutureReportsStorageFailure() throws Exception {
    SearchStorage failingStorage = Mockito.mock(SearchStorage.class);
    Mockito.doThrow(new RuntimeException("storage failure"))
        .when(failingStorage)
        .deleteMetalake("failing_metalake");

    try (SearchService failingService = newSearchServiceWithStorage(failingStorage)) {
      Future<?> deletion =
          failingService.removeMetadata(
              NameIdentifier.of("failing_metalake"), Entity.EntityType.METALAKE, true);
      ExecutionException exception =
          Assertions.assertThrows(
              ExecutionException.class, () -> deletion.get(10, TimeUnit.SECONDS));

      Assertions.assertTrue(
          exception
              .getCause()
              .getMessage()
              .contains("Failed to remove metalake metadata for failing_metalake"));
    }
  }

  @Test
  void testStaleDisableDoesNotOverrideNewerSynchronization() throws Exception {
    String metalake = "test_metalake";
    NameIdentifier metalakeIdentifier = NameIdentifier.of(metalake);
    SearchStorage trackingStorage = Mockito.mock(SearchStorage.class);
    CountDownLatch blockerStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);

    try (SearchService trackingService = newSearchServiceWithStorage(trackingStorage)) {
      Future<?> blocker = blockLifecycleExecutor(trackingService, blockerStarted, releaseBlocker);
      Assertions.assertTrue(blockerStarted.await(10, TimeUnit.SECONDS));

      Future<?> staleDisable = trackingService.updateMetalakeInUse(metalakeIdentifier, false);
      Future<Optional<SyncTask>> enable = trackingService.synchronizeMetalake(metalakeIdentifier);

      releaseBlocker.countDown();
      blocker.get(10, TimeUnit.SECONDS);
      staleDisable.get(10, TimeUnit.SECONDS);
      Assertions.assertTrue(enable.get(10, TimeUnit.SECONDS).isPresent());

      Mockito.verify(trackingStorage, Mockito.never()).updateMetalakeInUse(metalake, false);
    } finally {
      releaseBlocker.countDown();
    }
  }

  @Test
  void testDelayedActivationDoesNotOverrideNewerDisable() throws Exception {
    inMemorySearchStorage.clear();
    String metalake = "delayed_activation_metalake";
    NameIdentifier metalakeIdentifier = NameIdentifier.of(metalake);
    gravitinoService.createMetalake(metalake);
    CountDownLatch blockerStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    Future<?> blocker = blockLifecycleExecutor(searchService, blockerStarted, releaseBlocker);

    try {
      Assertions.assertTrue(blockerStarted.await(10, TimeUnit.SECONDS));
      Future<Optional<SyncTask>> delayedActivation =
          searchService.synchronizeMetalake(metalakeIdentifier);
      Future<?> disable = searchService.updateMetalakeInUse(metalakeIdentifier, false);

      releaseBlocker.countDown();
      blocker.get(10, TimeUnit.SECONDS);
      Optional<SyncTask> staleSyncTask = delayedActivation.get(10, TimeUnit.SECONDS);
      disable.get(10, TimeUnit.SECONDS);

      Assertions.assertFalse(staleSyncTask.isPresent());
      Assertions.assertTrue(
          inMemorySearchStorage.getSearchEntities().stream()
              .noneMatch(entity -> metalake.equals(entity.getMetalake())));
    } finally {
      releaseBlocker.countDown();
      inMemorySearchStorage.clear();
    }
  }

  @Test
  void testDroppedMetalakeRejectsStaleSyncWritesUntilReactivated() throws Exception {
    inMemorySearchStorage.clear();
    NameIdentifier metalakeIdentifier = NameIdentifier.of("test_metalake");
    SyncTask initialSync =
        searchService.synchronizeMetadata(metalakeIdentifier, Entity.EntityType.METALAKE, true);
    initialSync.waitToFinished();

    searchService.removeMetadata(metalakeIdentifier, Entity.EntityType.METALAKE, true).get();
    Assertions.assertTrue(inMemorySearchStorage.getSearchEntities().isEmpty());

    SearchEntityPO staleEntity =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(999L)
            .withEntityType(Entity.EntityType.SCHEMA)
            .withInUse(true)
            .withMetalake("test_metalake")
            .withEntityName("stale_schema")
            .withCatalogName("stale_catalog")
            .withFullQualifiedName("stale_catalog.stale_schema")
            .build();
    searchService.write(ImmutableList.of(staleEntity), WriteContext.DEFAULT);
    Assertions.assertTrue(inMemorySearchStorage.getSearchEntities().isEmpty());

    SyncTask reactivatedSync =
        searchService
            .synchronizeMetalake(metalakeIdentifier)
            .get()
            .orElseThrow(() -> new AssertionError("Metalake reactivation was superseded"));
    reactivatedSync.waitToFinished();
    Assertions.assertFalse(inMemorySearchStorage.getSearchEntities().isEmpty());
    inMemorySearchStorage.clear();
  }

  @Test
  void testFailedMetalakeReactivationKeepsStaleWritesBlocked() throws Exception {
    inMemorySearchStorage.clear();
    String metalake = "missing_metalake";
    NameIdentifier metalakeIdentifier = NameIdentifier.of(metalake);
    searchService.removeMetadata(metalakeIdentifier, Entity.EntityType.METALAKE, true).get();

    ExecutionException exception =
        Assertions.assertThrows(
            ExecutionException.class,
            () -> searchService.synchronizeMetalake(metalakeIdentifier).get());
    Assertions.assertTrue(exception.getCause() instanceof NoSuchMetalakeException);

    SearchEntityPO staleEntity =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(1000L)
            .withEntityType(Entity.EntityType.SCHEMA)
            .withInUse(true)
            .withMetalake(metalake)
            .withEntityName("stale_schema")
            .withCatalogName("stale_catalog")
            .withFullQualifiedName("stale_catalog.stale_schema")
            .build();
    searchService.write(ImmutableList.of(staleEntity), WriteContext.DEFAULT);

    Assertions.assertTrue(inMemorySearchStorage.getSearchEntities().isEmpty());
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
    inMemorySearchStorage.clear();
  }

  @Test
  void synchronizeEntityDataByTag() throws Exception {
    inMemorySearchStorage.clear();
    SyncTask task = searchService.synchronizeEntityDataByTag("test_metalake", "test_tag");
    task.waitToFinished();

    List<SearchEntityPO> searchEntityList = inMemorySearchStorage.getSearchEntities();
    Assertions.assertEquals(4, searchEntityList.size());
    inMemorySearchStorage.clear();
  }

  @Test
  void testResyncMetadataByTagHonorsTaskQueueLimit() {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL)).thenReturn("memory");
    Mockito.when(config.getAllConfig())
        .thenReturn(
            ImmutableMap.of(
                ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL.getKey(),
                "memory",
                ENTITY_GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE.getKey(),
                "0"));

    try (SearchService limitedQueueService = new SearchService(config)) {
      RuntimeException exception =
          Assertions.assertThrows(
              RuntimeException.class,
              () -> limitedQueueService.resyncMetadataByTag("test_metalake", "test_tag"));
      Assertions.assertTrue(exception.getMessage().contains("MaxQueueSize: 0"));
    }
  }

  void testSyncTaskWithoutCleanData(
      NameIdentifier nameIdentifier, Entity.EntityType type, boolean cascading, int expectedCount)
      throws Exception {
    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    MetadataObject testObj = NameIdentifierUtil.toMetadataObject(nameIdentifier, type);

    // Retry the sync until the expected count is reached. The orphan-removal step uses
    // `update_time < task.createTime`; if both timestamps land in the same millisecond the
    // deletion is skipped. Awaitility retries the full sync so that on the next attempt
    // enough wall-clock time has elapsed and the condition holds.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              SyncTask task = searchService.synchronizeMetadata(metalake, testObj, cascading);
              task.waitToFinished();

              TaskStatusDTO taskStatus = searchService.getTaskStatus(task.getTaskId());
              Assertions.assertNotNull(taskStatus);
              Assertions.assertEquals(task.getTaskId(), taskStatus.getTaskId());
              Assertions.assertEquals(
                  TaskStatus.TaskStatusEnum.COMPLETED.name(), taskStatus.getTaskStatus());

              Assertions.assertEquals(
                  expectedCount, inMemorySearchStorage.getSearchEntities().size());
            });

    List<SearchEntityPO> searchEntityList = inMemorySearchStorage.getSearchEntities();
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
  }

  @Test
  void testSyncWithDelete() throws Exception {
    // Synchronize all metadata
    NameIdentifier metalakeIdent = NameIdentifier.of("test_metalake");
    testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, 12);

    // The memory search storage should have 12 entities now.

    // Simulate catalog1 has been removed
    Map<String, CatalogInfo> original = gravitinoService.catalogs;

    Map<String, Integer> removeCatalogAndExpectMap = Maps.newHashMap();
    removeCatalogAndExpectMap.put("test_metalake.test_catalog1", 6);
    removeCatalogAndExpectMap.put("test_metalake.test_catalog2", 9);
    removeCatalogAndExpectMap.put("test_metalake.test_catalog3", 11);

    for (Map.Entry<String, Integer> entry : removeCatalogAndExpectMap.entrySet()) {
      String key = entry.getKey();
      CatalogInfo catalog = original.get(key);
      try {
        gravitinoService.catalogs.remove(key);
        testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, entry.getValue());
      } finally {
        original.put(key, catalog);
      }

      testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, 12);
    }

    // Now test remove schema
    Map<String, EntityCombinedSchema> originalSchemas = gravitinoService.schemas;
    Map<String, Integer> removeSchemaAndExpectMap = Maps.newHashMap();
    removeSchemaAndExpectMap.put("test_metalake.test_catalog1.test_schema1", 9);
    removeSchemaAndExpectMap.put("test_metalake.test_catalog1.test_schema2", 10);
    removeSchemaAndExpectMap.put("test_metalake.test_catalog2.test_schema1", 11);
    removeSchemaAndExpectMap.put("test_metalake.test_catalog2.test_schema2", 11);

    for (Map.Entry<String, Integer> entry : removeSchemaAndExpectMap.entrySet()) {
      String key = entry.getKey();
      EntityCombinedSchema schema = originalSchemas.get(key);
      try {
        gravitinoService.schemas.remove(key);
        testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, entry.getValue());
      } finally {
        originalSchemas.put(key, schema);
      }

      testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, 12);
    }

    // Start to test table
    Map<String, EntityCombinedTable> originalTables = gravitinoService.tables;
    Map<String, Integer> removeTableAndExpectMap = Maps.newHashMap();
    removeTableAndExpectMap.put("test_metalake.test_catalog1.test_schema1.test_table1", 11);
    removeTableAndExpectMap.put("test_metalake.test_catalog1.test_schema1.test_table2", 11);
    removeTableAndExpectMap.put("test_metalake.test_catalog1.test_schema2.test_table2", 11);

    for (Map.Entry<String, Integer> entry : removeTableAndExpectMap.entrySet()) {
      String key = entry.getKey();
      EntityCombinedTable table = originalTables.get(key);
      try {
        gravitinoService.tables.remove(key);
        testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, entry.getValue());
      } finally {
        originalTables.put(key, table);
      }

      testSyncTaskWithoutCleanData(metalakeIdent, METALAKE, true, 12);
    }
  }

  protected NameIdentifier getIdentifier(SearchEntityPO searchEntityPO) {
    return NameIdentifier.parse(
        searchEntityPO.getMetalake() + "." + searchEntityPO.getFullQualifiedName());
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to release the executor blocker");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private SearchService newMemorySearchService() {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL)).thenReturn("memory");
    Mockito.when(config.getAllConfig())
        .thenReturn(ImmutableMap.of(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL.getKey(), "memory"));
    return new SearchService(config);
  }

  private SearchService newSearchServiceWithStorage(SearchStorage storage)
      throws IllegalAccessException {
    SearchService service = newMemorySearchService();
    FieldUtils.writeField(service, "storage", storage, true);
    return service;
  }

  private Future<?> blockLifecycleExecutor(
      SearchService service, CountDownLatch blockerStarted, CountDownLatch releaseBlocker)
      throws IllegalAccessException {
    ExecutorService executorService =
        (ExecutorService) FieldUtils.readField(service, "executorService", true);
    return executorService.submit(
        () -> {
          blockerStarted.countDown();
          awaitLatch(releaseBlocker);
        });
  }
}
