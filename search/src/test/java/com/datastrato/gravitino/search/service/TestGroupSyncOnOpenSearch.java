/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.GROUP;
import static org.apache.gravitino.Entity.EntityType.METALAKE;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.listener.GroupEventHandler;
import com.datastrato.gravitino.search.store.opensearch.OpenSearchConfig;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AddGroupEvent;
import org.apache.gravitino.listener.api.event.RemoveGroupEvent;
import org.apache.gravitino.listener.api.info.GroupInfo;
import org.apache.gravitino.meta.GroupEntity;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/** Exercises Group synchronization and querying against a real OpenSearch instance. */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestGroupSyncOnOpenSearch {
  private static final String METALAKE_NAME = "test_metalake";

  private OpenSearchContainer container;
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;

  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-group-sync",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                DEFAULT_PASSWORD));
    container.start();
    createIndexTemplates();

    gravitinoService = new MockedGravitinoService();
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL)).thenReturn("opensearch");
    Mockito.when(config.getAllConfig())
        .thenReturn(
            ImmutableMap.of(
                ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL.getKey(),
                "opensearch",
                OpenSearchConfig.OPEN_SEARCH_URL_KEY,
                container.getOpenSearchUrl(),
                OpenSearchConfig.OPEN_SEARCH_USERNAME_KEY,
                DEFAULT_USERNAME,
                OpenSearchConfig.OPEN_SEARCH_PASSWORD_KEY,
                DEFAULT_PASSWORD));

    searchService = gravitinoService.createMokedSearchService(new SearchService(config));
    gravitinoService.createMetalake(METALAKE_NAME);
    gravitinoService.createGroup(METALAKE_NAME, "data_engineers");
  }

  @AfterAll
  public void tearDown() {
    if (searchService != null) {
      searchService.close();
    }
    if (container != null) {
      container.stop();
    }
  }

  @Test
  void testFullSyncIndexesSparseGroupAndSearchesName() {
    SyncTask task =
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true);
    awaitCompletion(task);

    SearchEntityDTO group = queryGroup("engineer", "data_engineers");
    Assertions.assertEquals(GROUP, group.getEntityType());
    Assertions.assertEquals(METALAKE_NAME, group.getMetalake());
    Assertions.assertNull(group.getEntityComment());
    Assertions.assertNull(group.getCatalogName());
    Assertions.assertNull(group.getFullQualifiedName());

    Assertions.assertDoesNotThrow(
        () -> searchService.query(METALAKE_NAME, "no_such_keyword", 0, 10));
  }

  @Test
  void testGroupEventsAddAndRemoveDocument() {
    GroupEventHandler handler = new GroupEventHandler(searchService);
    GroupEntity eventGroup = gravitinoService.createGroup(METALAKE_NAME, "event_group");
    handler.handleEvent(new AddGroupEvent("tester", METALAKE_NAME, new GroupInfo(eventGroup)));

    awaitGroup("event_group", true);

    gravitinoService.removeGroup(METALAKE_NAME, "event_group");
    handler.handleEvent(new RemoveGroupEvent("tester", METALAKE_NAME, "event_group", true));

    awaitGroup("event_group", false);
  }

  private SearchEntityDTO queryGroup(String keyword, String groupName) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, keyword, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, GROUP);
    Assertions.assertNotNull(dto, "No Group matched keyword " + keyword);
    return dto.getEntities().stream()
        .filter(entity -> groupName.equals(entity.getEntityName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Group " + groupName + " was not returned"));
  }

  private void awaitGroup(String groupName, boolean expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              SearchEntitiesDTO groups =
                  getSearchEntitiesDTOByType(
                      searchService.query(METALAKE_NAME, groupName, 0, 10), GROUP);
              boolean found =
                  groups != null
                      && groups.getEntities().stream()
                          .anyMatch(entity -> groupName.equals(entity.getEntityName()));
              Assertions.assertEquals(expected, found);
            });
  }

  private void awaitCompletion(SyncTask task) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              TaskStatusDTO status = searchService.getTaskStatus(task.getTaskId());
              Assertions.assertNotNull(status);
              Assertions.assertEquals(
                  TaskStatus.TaskStatusEnum.COMPLETED.name(), status.getTaskStatus());
            });
  }

  private void createIndexTemplates() throws Exception {
    String bin = Paths.get(System.getProperty("user.dir"), "..", "bin").normalize().toString();
    Process process =
        new ProcessBuilder(
                ImmutableList.of(
                    "/bin/bash",
                    bin + "/opensearch/create_indices_template.sh.template",
                    "v2",
                    container.getOpenSearchUrl(),
                    DEFAULT_USERNAME,
                    DEFAULT_PASSWORD))
            .directory(new File(bin))
            .redirectErrorStream(true)
            .start();
    try {
      String output = readFully(process.getInputStream());
      Assertions.assertEquals(0, process.waitFor(), "Failed to create index templates: " + output);
    } finally {
      process.destroy();
    }
  }

  private static String readFully(InputStream stream) throws Exception {
    try (InputStream input = stream) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read;
      while ((read = input.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return buffer.toString(StandardCharsets.UTF_8.name());
    }
  }
}
