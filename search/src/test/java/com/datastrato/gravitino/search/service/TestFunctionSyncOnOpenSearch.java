/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.FUNCTION;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.TABLE;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.listener.FunctionEventHandler;
import com.datastrato.gravitino.search.store.opensearch.OpenSearchConfig;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.apache.gravitino.Config;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.function.FunctionChange;
import org.apache.gravitino.listener.api.event.function.AlterFunctionEvent;
import org.apache.gravitino.listener.api.event.function.RegisterFunctionEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * Drives Function synchronization against a real OpenSearch instance, covering the full sync and
 * event paths through the v2 Function index template.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestFunctionSyncOnOpenSearch {

  private static final String METALAKE_NAME = "test_metalake";
  private static final NameIdentifier FUNCTION_IDENTIFIER =
      NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", "mask_email");

  private OpenSearchContainer container;
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;

  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-function-sync",
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
    gravitinoService.putFunction(FUNCTION_IDENTIFIER, "masks sensitive email addresses");
    gravitinoService.addTagsToObject(FUNCTION_IDENTIFIER, ImmutableSet.of("pii"));
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
  void testFunctionIsIndexedByAFullMetalakeSync() throws Exception {
    SyncTask task =
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true);
    awaitCompletion(task);

    SearchEntityDTO function = queryFunction("mask_email", "mask_email");
    Assertions.assertEquals(FUNCTION, function.getEntityType());
    Assertions.assertEquals(METALAKE_NAME, function.getMetalake());
    Assertions.assertEquals(
        "test_catalog1.test_schema1.mask_email", function.getFullQualifiedName());

    Assertions.assertEquals("mask_email", queryFunction("sensitive", "mask_email").getEntityName());
    Assertions.assertEquals("mask_email", queryFunction("pii", "mask_email").getEntityName());

    SearchEntitiesDTO tables =
        getSearchEntitiesDTOByType(searchService.query(METALAKE_NAME, "test_table1", 0, 10), TABLE);
    Assertions.assertNotNull(tables);
    Assertions.assertEquals(1, tables.getTotalSize());
  }

  @Test
  void testFunctionEventsUpdateTheIndex() {
    NameIdentifier identifier =
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", "event_function");
    gravitinoService.putFunction(identifier, "created by an event");
    FunctionEventHandler handler = new FunctionEventHandler(searchService);
    handler.handleEvent(new RegisterFunctionEvent("tester", identifier, null));

    awaitFunction(
        "event_function",
        function -> Assertions.assertEquals("created by an event", function.getEntityComment()));

    gravitinoService.putFunction(identifier, "updated by an event");
    handler.handleEvent(
        new AlterFunctionEvent(
            "tester",
            identifier,
            new FunctionChange[] {FunctionChange.updateComment("updated by an event")},
            null));

    awaitFunction(
        "event_function",
        function -> Assertions.assertEquals("updated by an event", function.getEntityComment()));
  }

  private void awaitFunction(String functionName, Consumer<SearchEntityDTO> assertion) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertion.accept(queryFunction(functionName, functionName)));
  }

  private SearchEntityDTO queryFunction(String keyword, String functionName) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, keyword, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, FUNCTION);
    Assertions.assertNotNull(dto, "No function matched the keyword " + keyword);
    return dto.getEntities().stream()
        .filter(entity -> functionName.equals(entity.getEntityName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Function "
                        + functionName
                        + " was not among the hits for the keyword "
                        + keyword));
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
