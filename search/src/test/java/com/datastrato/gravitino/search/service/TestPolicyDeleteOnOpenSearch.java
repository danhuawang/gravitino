/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.POLICY;
import static org.apache.gravitino.Entity.EntityType.TABLE;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.listener.PolicyEventHandler;
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
import org.apache.gravitino.Config;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.policy.DeletePolicyEvent;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * Covers what happens to the index when a policy is deleted, driving the whole chain: the policy
 * event, the event handler, the sync task and a real OpenSearch instance.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestPolicyDeleteOnOpenSearch {

  private static final String METALAKE_NAME = "test_metalake";
  private static final String POLICY_NAME = "retention";
  private static final NameIdentifier TABLE_IDENT =
      NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", "test_table1");

  private OpenSearchContainer container;
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;

  /**
   * Starts OpenSearch, provisions the index templates and wires a search service backed by it,
   * along with the mocked Gravitino metadata the test synchronizes.
   *
   * @throws Exception If OpenSearch or the index templates cannot be prepared.
   */
  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-policy-delete",
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
    gravitinoService.createTable(TABLE_IDENT);
    gravitinoService.createPolicy(POLICY_NAME);
    gravitinoService.addPoliciesToObject(TABLE_IDENT, ImmutableSet.of(POLICY_NAME));
  }

  /** Closes the search service and stops the OpenSearch container. */
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
  void testDeletingAPolicyRemovesItsDocumentAndKeepsTheEntities() {
    awaitCompletion(
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true));

    Assertions.assertNotNull(findPolicy(), "the policy should be indexed by the full sync");
    SearchEntityDTO table = querySingleTable();
    Assertions.assertEquals(
        ImmutableSet.of(POLICY_NAME), ImmutableSet.copyOf(table.getPolicyNames()));

    gravitinoService.deletePolicy(POLICY_NAME);
    new PolicyEventHandler(searchService)
        .handleEvent(
            new DeletePolicyEvent(
                "tester", NameIdentifierUtil.ofPolicy(METALAKE_NAME, POLICY_NAME), true));

    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Assertions.assertNull(findPolicy(), "the policy document should be removed");

              // The table keeps its document and only loses the policy.
              SearchEntityDTO resynced = querySingleTable();
              Assertions.assertTrue(
                  resynced.getPolicyNames() == null || resynced.getPolicyNames().isEmpty(),
                  "the deleted policy should have dropped off the table");
            });
  }

  private SearchEntityDTO findPolicy() {
    SearchEntitiesDTO dto =
        getSearchEntitiesDTOByType(searchService.query(METALAKE_NAME, POLICY_NAME, 0, 10), POLICY);
    if (dto == null || dto.getEntities().isEmpty()) {
      return null;
    }
    return dto.getEntities().stream()
        .filter(entity -> POLICY_NAME.equals(entity.getEntityName()))
        .findFirst()
        .orElse(null);
  }

  private SearchEntityDTO querySingleTable() {
    SearchEntitiesDTO dto =
        getSearchEntitiesDTOByType(searchService.query(METALAKE_NAME, "test_table1", 0, 10), TABLE);
    Assertions.assertNotNull(dto, "the table should stay searchable");
    Assertions.assertEquals(1, dto.getTotalSize());
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
