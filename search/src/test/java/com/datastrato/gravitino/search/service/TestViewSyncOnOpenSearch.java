/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.TABLE;
import static org.apache.gravitino.Entity.EntityType.VIEW;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchViewEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
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
import org.apache.gravitino.Config;
import org.apache.gravitino.NameIdentifier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * Drives a full metalake synchronization against a real OpenSearch instance, so that the view
 * indexing path is exercised end to end: sync task, entity source, converter, OpenSearch write and
 * query. The other view tests either stop at the converter or write a pre-built document.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestViewSyncOnOpenSearch {

  private static final String METALAKE_NAME = "test_metalake";
  private static final String VIEW_NAME = "daily_orders";

  private OpenSearchContainer container;
  private SearchService searchService;

  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-view-sync",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                DEFAULT_PASSWORD));
    container.start();
    createIndexTemplates();

    MockedGravitinoService gravitinoService = new MockedGravitinoService();
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
    gravitinoService.createView(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", VIEW_NAME));
    gravitinoService.addTagsToObject(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", VIEW_NAME),
        ImmutableSet.of("test_tag"));
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
  void testViewIsIndexedByAFullMetalakeSync() throws Exception {
    SyncTask task =
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true);
    awaitCompletion(task);

    SearchEntityDTO view = querySingleView(VIEW_NAME);
    Assertions.assertEquals(VIEW_NAME, view.getEntityName());
    Assertions.assertEquals(VIEW, view.getEntityType());
    Assertions.assertEquals(METALAKE_NAME, view.getMetalake());
    Assertions.assertEquals("test_catalog1.test_schema1." + VIEW_NAME, view.getFullQualifiedName());

    SearchViewEntityDTO viewDTO = (SearchViewEntityDTO) view;
    Assertions.assertEquals(1, viewDTO.getColumns().size());
    Assertions.assertEquals("test_view_column", viewDTO.getColumns().get(0).getColumnName());

    // The view must be reachable by its comment, a column name, a tag and a property as well. The
    // property clause only matches because the v2 view mapping indexes entity_properties.
    Assertions.assertEquals(VIEW_NAME, querySingleView("test view").getEntityName());
    Assertions.assertEquals(VIEW_NAME, querySingleView("test_view_column").getEntityName());
    Assertions.assertEquals(VIEW_NAME, querySingleView("test_tag").getEntityName());
    Assertions.assertEquals(VIEW_NAME, querySingleView("incremental").getEntityName());

    // The table in the same schema is still indexed, the two paths coexist.
    SearchEntitiesDTO tables =
        getSearchEntitiesDTOByType(searchService.query(METALAKE_NAME, "test_table1", 0, 10), TABLE);
    Assertions.assertNotNull(tables);
    Assertions.assertEquals(1, tables.getTotalSize());
  }

  private SearchEntityDTO querySingleView(String keyword) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, keyword, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, VIEW);
    Assertions.assertNotNull(dto, "No view matched the keyword " + keyword);
    Assertions.assertEquals(1, dto.getTotalSize(), "Unexpected view hits for " + keyword);
    return dto.getEntities().get(0);
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

  /**
   * Provisions the index templates with the script the distribution ships, so that the test covers
   * the same provisioning path an operator runs rather than a Java reimplementation of it.
   */
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
