/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.TABLE;
import static org.apache.gravitino.Entity.EntityType.TAG;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.listener.TagEventHandler;
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
import java.util.Map;
import java.util.function.Consumer;
import org.apache.gravitino.Config;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.AlterTagEvent;
import org.apache.gravitino.listener.api.event.CreateTagEvent;
import org.apache.gravitino.listener.api.info.TagInfo;
import org.apache.gravitino.tag.TagChange;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * Drives a full metalake synchronization against a real OpenSearch instance, so that the tag
 * indexing path is exercised end to end: sync task, entity source, converter, OpenSearch write and
 * query. The other tag tests either stop at the converter or write a pre-built document.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestTagSyncOnOpenSearch {

  private static final String METALAKE_NAME = "test_metalake";

  private OpenSearchContainer container;
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;

  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-tag-sync",
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
    gravitinoService.addTagsToObject(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", "test_table1"),
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
  void testTagIsIndexedByAFullMetalakeSync() throws Exception {
    SyncTask task =
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true);
    awaitCompletion(task);

    SearchEntityDTO tag = queryTag("test_tag", "test_tag");
    Assertions.assertEquals("test_tag", tag.getEntityName());
    Assertions.assertEquals(TAG, tag.getEntityType());
    Assertions.assertEquals(METALAKE_NAME, tag.getMetalake());

    // The tag must be reachable by its comment and by a property value as well.
    Assertions.assertEquals("test_tag", queryTag("test_tag_comment", "test_tag").getEntityName());
    Assertions.assertEquals("test_tag", queryTag("value", "test_tag").getEntityName());

    // The tagged table is still indexed and still carries the tag, the two paths coexist.
    SearchEntitiesDTO tables =
        getSearchEntitiesDTOByType(searchService.query(METALAKE_NAME, "test_table1", 0, 10), TABLE);
    Assertions.assertNotNull(tables);
    Assertions.assertTrue(
        tables.getEntities().stream()
            .anyMatch(entity -> "test_table1".equals(entity.getEntityName())));

    // A tag filter is sent to every entity index, including the tag index, whose mapping does not
    // contain the nested "tags" field. That unmapped index must not make the whole query fail.
    SearchEntitiesDTO taggedTables =
        getSearchEntitiesDTOByType(
            searchService.query(METALAKE_NAME, "tag_name:test_tag", 0, 10), TABLE);
    Assertions.assertNotNull(taggedTables);
    Assertions.assertEquals(1, taggedTables.getTotalSize());
  }

  @Test
  void testTagCreatedByAnEventIsIndexed() {
    gravitinoService.putTag(
        "created_by_event", "born from a create event", ImmutableMap.of("origin", "event"));

    new TagEventHandler(searchService)
        .handleEvent(
            new CreateTagEvent(
                "tester",
                METALAKE_NAME,
                new TagInfo(
                    "created_by_event",
                    "born from a create event",
                    ImmutableMap.of("origin", "event"))));

    awaitTag(
        "created_by_event",
        tag -> Assertions.assertEquals("born from a create event", tag.getEntityComment()));
  }

  @Test
  void testTagAlteredByAnEventIsReindexed() {
    gravitinoService.putTag("altered_by_event", "first comment", ImmutableMap.of());
    new TagEventHandler(searchService)
        .handleEvent(
            new CreateTagEvent(
                "tester",
                METALAKE_NAME,
                new TagInfo("altered_by_event", "first comment", ImmutableMap.of())));
    awaitTag(
        "altered_by_event",
        tag -> Assertions.assertEquals("first comment", tag.getEntityComment()));

    // The comment changes server side, the alter event has to bring the index back in line.
    gravitinoService.putTag("altered_by_event", "second comment", ImmutableMap.of());
    new TagEventHandler(searchService)
        .handleEvent(
            new AlterTagEvent(
                "tester",
                METALAKE_NAME,
                new TagChange[] {TagChange.updateComment("second comment")},
                new TagInfo("altered_by_event", "second comment", ImmutableMap.of())));

    awaitTag(
        "altered_by_event",
        tag -> Assertions.assertEquals("second comment", tag.getEntityComment()));
  }

  @Test
  void testTagRenamedByAnEventReplacesTagAndRefreshesAssociatedEntity() throws Exception {
    String oldTagName = "rename_source_tag";
    String newTagName = "rename_target_tag";
    String tableName = "rename_target_table";
    String newComment = "renamed tag comment";
    ImmutableMap<String, String> newProperties = ImmutableMap.of("stage", "renamed");
    NameIdentifier tableIdentifier =
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", tableName);

    gravitinoService.putTag(oldTagName, "original tag comment", ImmutableMap.of("stage", "old"));
    gravitinoService.createTable(tableIdentifier);
    gravitinoService.addTagsToObject(tableIdentifier, ImmutableSet.of(oldTagName));

    TagEventHandler handler = new TagEventHandler(searchService);
    handler.handleEvent(
        new CreateTagEvent(
            "tester",
            METALAKE_NAME,
            new TagInfo(oldTagName, "original tag comment", ImmutableMap.of("stage", "old"))));
    SyncTask tableSyncTask = searchService.synchronizeMetadata(tableIdentifier, TABLE, false);
    awaitCompletion(tableSyncTask);

    awaitTag(
        oldTagName, tag -> Assertions.assertEquals("original tag comment", tag.getEntityComment()));
    long originalTagId = queryTag(oldTagName, oldTagName).getEntityId();
    awaitTableTag(tableName, oldTagName, "original tag comment", ImmutableMap.of("stage", "old"));

    gravitinoService.renameTag(oldTagName, newTagName, newComment, newProperties);
    handler.handleEvent(
        new AlterTagEvent(
            "tester",
            METALAKE_NAME,
            new TagChange[] {TagChange.rename(newTagName)},
            new TagInfo(newTagName, newComment, newProperties)));

    awaitTag(
        newTagName,
        tag -> {
          Assertions.assertEquals(originalTagId, tag.getEntityId());
          Assertions.assertEquals(newComment, tag.getEntityComment());
          Assertions.assertEquals(1, tag.getEntityProperties().size());
          Assertions.assertEquals("stage", tag.getEntityProperties().get(0).getKey());
          Assertions.assertEquals("renamed", tag.getEntityProperties().get(0).getValue());
        });
    awaitTagMissing(oldTagName);
    awaitTableTag(tableName, newTagName, newComment, newProperties);
  }

  private void awaitTag(String tagName, Consumer<SearchEntityDTO> assertion) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertion.accept(queryTag(tagName, tagName)));
  }

  private void awaitTagMissing(String tagName) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              SearchEntitiesDTO tags =
                  getSearchEntitiesDTOByType(
                      searchService.query(METALAKE_NAME, tagName, 0, 10), TAG);
              if (tags != null) {
                Assertions.assertTrue(
                    tags.getEntities().stream()
                        .noneMatch(entity -> tagName.equals(entity.getEntityName())));
              }
            });
  }

  private void awaitTableTag(
      String tableName, String tagName, String tagComment, Map<String, String> properties) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              SearchEntityDTO table = queryTable(tableName);
              SearchEntityDTO.SearchTagDTO tag =
                  table.getTags().stream()
                      .filter(candidate -> tagName.equals(candidate.getTagName()))
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new AssertionError(
                                  "Tag " + tagName + " was not indexed on table " + tableName));
              Assertions.assertEquals(tagComment, tag.getTagComment());
              Assertions.assertEquals(properties, tag.getProperties());
            });
  }

  /**
   * Looks a tag up by keyword and picks the expected one out of the hits. A keyword can
   * legitimately match several tags, the index maps "_" to a word separator, so the hit count is
   * not asserted.
   */
  private SearchEntityDTO queryTag(String keyword, String tagName) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, keyword, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, TAG);
    Assertions.assertNotNull(dto, "No tag matched the keyword " + keyword);
    return dto.getEntities().stream()
        .filter(entity -> tagName.equals(entity.getEntityName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Tag " + tagName + " was not among the hits for the keyword " + keyword));
  }

  private SearchEntityDTO queryTable(String tableName) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, tableName, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, TABLE);
    Assertions.assertNotNull(dto, "No table matched the keyword " + tableName);
    return dto.getEntities().stream()
        .filter(entity -> tableName.equals(entity.getEntityName()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("Table " + tableName + " was not among the search hits"));
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
