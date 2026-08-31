/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.TABLE;
import static org.apache.gravitino.Entity.EntityType.USER;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.listener.UserEventHandler;
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
import org.apache.gravitino.listener.api.event.AddUserEvent;
import org.apache.gravitino.listener.api.event.RemoveUserEvent;
import org.apache.gravitino.listener.api.info.UserInfo;
import org.apache.gravitino.meta.UserEntity;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/** Exercises User synchronization and querying against a real OpenSearch instance. */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestUserSyncOnOpenSearch {
  private static final String METALAKE_NAME = "test_metalake";

  private OpenSearchContainer container;
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;

  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-user-sync",
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
    gravitinoService.createCatalog(NameIdentifier.of(METALAKE_NAME, "test_catalog1"));
    gravitinoService.createSchema(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1"));
    gravitinoService.createTable(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", "test_table1"));
    gravitinoService.createUser(METALAKE_NAME, "alice_analyst");
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
  void testFullSyncIndexesSparseUserAndSearchesName() {
    SyncTask task =
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true);
    awaitCompletion(task);

    SearchEntityDTO user = queryUser("alice", "alice_analyst");
    Assertions.assertEquals(USER, user.getEntityType());
    Assertions.assertEquals(METALAKE_NAME, user.getMetalake());
    Assertions.assertNull(user.getEntityComment());
    Assertions.assertNull(user.getCatalogName());
    Assertions.assertNull(user.getFullQualifiedName());

    SearchEntitiesDTO tables =
        getSearchEntitiesDTOByType(searchService.query(METALAKE_NAME, "test table", 0, 10), TABLE);
    Assertions.assertNotNull(tables);
    Assertions.assertEquals(1, tables.getTotalSize());

    Assertions.assertDoesNotThrow(
        () -> searchService.query(METALAKE_NAME, "no_such_keyword", 0, 10));
  }

  @Test
  void testUserEventsAddAndRemoveDocument() {
    UserEventHandler handler = new UserEventHandler(searchService);
    UserEntity eventUser = gravitinoService.createUser(METALAKE_NAME, "event_user");
    handler.handleEvent(new AddUserEvent("tester", METALAKE_NAME, new UserInfo(eventUser)));

    awaitUser("event_user", true);

    gravitinoService.removeUser(METALAKE_NAME, "event_user");
    handler.handleEvent(new RemoveUserEvent("tester", METALAKE_NAME, "event_user", true));

    awaitUser("event_user", false);
  }

  private SearchEntityDTO queryUser(String keyword, String userName) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, keyword, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, USER);
    Assertions.assertNotNull(dto, "No User matched keyword " + keyword);
    return dto.getEntities().stream()
        .filter(entity -> userName.equals(entity.getEntityName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("User " + userName + " was not returned"));
  }

  private void awaitUser(String userName, boolean expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              SearchEntitiesDTO users =
                  getSearchEntitiesDTOByType(
                      searchService.query(METALAKE_NAME, userName, 0, 10), USER);
              boolean found =
                  users != null
                      && users.getEntities().stream()
                          .anyMatch(entity -> userName.equals(entity.getEntityName()));
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
