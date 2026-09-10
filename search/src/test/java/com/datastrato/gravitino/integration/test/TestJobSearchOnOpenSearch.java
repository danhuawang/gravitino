/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static java.util.Collections.emptyMap;
import static org.apache.gravitino.Entity.EntityType.JOB_TEMPLATE;
import static org.apache.gravitino.Entity.EntityType.TAG;

import com.datastrato.gravitino.search.client.SearchClient;
import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.job.ShellJobTemplate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Covers job template search against a real OpenSearch backend.
 *
 * <p>Verifies that hyphenated keywords match literal substrings consistently for job templates and
 * tags, while ordinary terms can match across the name and comment fields.
 *
 * <p>It also covers the index scope a job template shares with a tag of the same name: both live
 * directly under the metalake, so deleting one must not remove the other.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestJobSearchOnOpenSearch extends BaseIT {

  private static final String METALAKE_NAME = "test_job_search_metalake";

  /**
   * Carried by both a tag and a job template, so a deletion of one is visible on the other. Shares
   * its first token with {@link #SIBLING_TEMPLATE_NAME}, so matching one token is not enough.
   */
  private static final String SHARED_NAME = "daily-etl";

  private static final String SIBLING_TEMPLATE_NAME = "daily-report";

  /** Its name holds one token of the query and its comment the other, never both in one field. */
  private static final String SPLIT_TAG_NAME = "s3";

  private static final String LITERAL_TAG_NAME = "s3-bucket";

  private static final String SPLIT_TAG_COMMENT = "bucket for raw logs";

  private OpenSearchContainer openSearchContainer;
  private GravitinoMetalake metalake;
  private SearchClient searchClient;
  private File stagingDir;

  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    openSearchContainer = createOpenSearchContainer();
    OpenSearchIndexTemplates.create(openSearchContainer, OpenSearchIndexTemplates.CURRENT_VERSION);
    stagingDir = Files.createTempDirectory("test_job_search_staging").toFile();

    Map<String, String> configs = new HashMap<>();
    configs.put("gravitino.eventListener.names", "search");
    configs.put("gravitino.datastrato.search.storage.impl", "opensearch");
    configs.put(
        "gravitino.datastrato.search.opensearch.url", openSearchContainer.getOpenSearchUrl());
    configs.put(
        "gravitino.datastrato.search.opensearch.username", OpenSearchContainer.DEFAULT_USERNAME);
    configs.put(
        "gravitino.datastrato.search.opensearch.password", OpenSearchContainer.DEFAULT_PASSWORD);
    configs.put("gravitino.job.stagingDir", stagingDir.getAbsolutePath());
    registerCustomConfigs(configs);

    super.startIntegrationTest();
    searchClient = new SearchClient("http://localhost:" + getGravitinoServerPort());

    client.createMetalake(METALAKE_NAME, "comment", emptyMap());
    metalake = client.loadMetalake(METALAKE_NAME);

    registerJobTemplate(SHARED_NAME, "nightly extract transform load");
    registerJobTemplate(SIBLING_TEMPLATE_NAME, "morning summary");
    metalake.createTag(SHARED_NAME, "tag that shares the job template name", emptyMap());
    metalake.createTag(SPLIT_TAG_NAME, SPLIT_TAG_COMMENT, emptyMap());
    metalake.createTag(LITERAL_TAG_NAME, "literal name", emptyMap());

    awaitIndexed(SHARED_NAME, JOB_TEMPLATE, SHARED_NAME);
    awaitIndexed(SIBLING_TEMPLATE_NAME, JOB_TEMPLATE, SIBLING_TEMPLATE_NAME);
    awaitIndexed(SHARED_NAME, TAG, SHARED_NAME);
    awaitIndexed(SPLIT_TAG_NAME, TAG, SPLIT_TAG_NAME);
    awaitIndexed(LITERAL_TAG_NAME, TAG, LITERAL_TAG_NAME);
  }

  @AfterAll
  @Override
  public void stopIntegrationTest() throws IOException, InterruptedException {
    super.stopIntegrationTest();
    if (searchClient != null) {
      searchClient.close();
    }
    if (openSearchContainer != null) {
      openSearchContainer.stop();
    }
  }

  @Test
  void testHyphenatedNameMatchesOnlyTheJobTemplateItNames() throws Exception {
    // Literal matching must not return a sibling that only shares the "daily" prefix.
    Assertions.assertEquals(Set.of(SHARED_NAME), namesOf(search(SHARED_NAME), JOB_TEMPLATE));
  }

  @Test
  void testHyphenatedKeywordsMatchLiteralNamesAcrossEntityTypes() throws Exception {
    Assertions.assertEquals(Set.of(LITERAL_TAG_NAME), namesOf(search("s3-bucket"), TAG));
    Assertions.assertEquals(Set.of(LITERAL_TAG_NAME), namesOf(search("3-b"), TAG));
    Assertions.assertEquals(Set.of(LITERAL_TAG_NAME), namesOf(search("-bucket"), TAG));
    Assertions.assertEquals(Set.of(SHARED_NAME), namesOf(search("y-e"), JOB_TEMPLATE));
  }

  @Test
  void testOrdinaryKeywordsStillMatchAcrossFields() throws Exception {
    // Ordinary terms can match the name and comment independently; the hyphen is literal.
    Assertions.assertEquals(
        Set.of(SPLIT_TAG_NAME, LITERAL_TAG_NAME), namesOf(search("s3 bucket"), TAG));
    Assertions.assertEquals(Set.of(LITERAL_TAG_NAME), namesOf(search("\"s3-bucket\""), TAG));
  }

  @Test
  void testDeletingATagKeepsTheJobTemplateOfTheSameName() throws Exception {
    Assertions.assertEquals(Set.of(SHARED_NAME), namesOf(search(SHARED_NAME), TAG));

    metalake.deleteTag(SHARED_NAME);

    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> Assertions.assertTrue(namesOf(search(SHARED_NAME), TAG).isEmpty()));

    Assertions.assertEquals(
        Set.of(SHARED_NAME),
        namesOf(search(SHARED_NAME), JOB_TEMPLATE),
        "Deleting the tag must leave the job template of the same name searchable");
  }

  private void registerJobTemplate(String name, String comment) {
    metalake.registerJobTemplate(
        ShellJobTemplate.builder()
            .withName(name)
            .withComment(comment)
            .withExecutable("/opt/jobs/" + name + ".sh")
            .withScripts(Collections.emptyList())
            .withCustomFields(Collections.emptyMap())
            .build());
  }

  private List<SearchEntitiesDTO> search(String keyword) throws Exception {
    return searchClient.search(keyword, METALAKE_NAME);
  }

  private static Set<String> namesOf(List<SearchEntitiesDTO> result, Entity.EntityType type) {
    SearchEntitiesDTO group = getSearchEntitiesDTOByType(result, type);
    if (group == null) {
      return Set.of();
    }
    return group.getEntities().stream()
        .map(SearchEntityDTO::getEntityName)
        .collect(Collectors.toSet());
  }

  private void awaitIndexed(String keyword, Entity.EntityType type, String name) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                Assertions.assertTrue(
                    namesOf(search(keyword), type).contains(name),
                    "Not indexed yet: " + type + " " + name));
  }

  private OpenSearchContainer createOpenSearchContainer() {
    OpenSearchContainer container =
        new OpenSearchContainer(
            "ci-opensearch-job-template-search",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                OpenSearchContainer.DEFAULT_PASSWORD));
    container.start();
    return container;
  }
}
