/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static java.util.Collections.emptyMap;
import static org.apache.gravitino.Entity.EntityType.TABLE;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.types.Types;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Verifies that deleting a tag removes the tag from an indexed entity without removing the entity
 * itself from OpenSearch.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestTagDeleteOnOpenSearch extends BaseIT {

  private static final String METALAKE_NAME = "test_metalake";
  private static final String CATALOG_NAME = "test_catalog";
  private static final String SCHEMA_NAME = "test_schema";
  private static final String TABLE_NAME = "test_table";
  private static final String TAG_NAME = "test_tag";
  private static final String INDEX_TEMPLATE_VERSION = "v2";

  private OpenSearchContainer openSearchContainer;
  private MySQLContainer mySQLContainer;
  private GravitinoMetalake metalake;
  private SearchClient searchClient;

  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    openSearchContainer = createOpenSearchContainer();
    createIndexTemplates();
    mySQLContainer = createMySQLContainer();

    Map<String, String> configs = new HashMap<>();
    configs.put("gravitino.eventListener.names", "search");
    configs.put("gravitino.datastrato.search.storage.impl", "opensearch");
    configs.put(
        "gravitino.datastrato.search.opensearch.url", openSearchContainer.getOpenSearchUrl());
    configs.put(
        "gravitino.datastrato.search.opensearch.username", OpenSearchContainer.DEFAULT_USERNAME);
    configs.put(
        "gravitino.datastrato.search.opensearch.password", OpenSearchContainer.DEFAULT_PASSWORD);
    registerCustomConfigs(configs);

    super.startIntegrationTest();
    searchClient = new SearchClient("http://localhost:" + getGravitinoServerPort());

    client.createMetalake(METALAKE_NAME, "comment", emptyMap());
    metalake = client.loadMetalake(METALAKE_NAME);
    Catalog catalog = createMySQLCatalog();
    Schema schema = catalog.asSchemas().createSchema(SCHEMA_NAME, "", emptyMap());
    Table table = createTable(catalog, schema.name());

    // Wait for the table creation event before triggering another sync for the same table.
    waitUntilTableIsIndexed();

    metalake.createTag(TAG_NAME, "comment", emptyMap());
    table.supportsTags().associateTags(new String[] {TAG_NAME}, null);
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
    if (mySQLContainer != null) {
      mySQLContainer.close();
    }
  }

  @Test
  void testDeletingATagKeepsTheTaggedEntitySearchable() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              SearchEntityDTO table = querySingleTable();
              Assertions.assertNotNull(table.getTags());
              Assertions.assertEquals(1, table.getTags().size());
              Assertions.assertEquals(TAG_NAME, table.getTags().get(0).getTagName());
            });

    metalake.deleteTag(TAG_NAME);

    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              SearchEntityDTO table = querySingleTable();
              Assertions.assertTrue(table.getTags() == null || table.getTags().isEmpty());
            });
  }

  private void waitUntilTableIsIndexed() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> querySingleTable());
  }

  private OpenSearchContainer createOpenSearchContainer() {
    OpenSearchContainer container =
        new OpenSearchContainer(
            "ci-opensearch-tag-delete",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                OpenSearchContainer.DEFAULT_PASSWORD));
    container.start();
    return container;
  }

  private MySQLContainer createMySQLContainer() {
    MySQLContainer container =
        MySQLContainer.builder()
            .withEnvVars(ImmutableMap.of("MYSQL_ROOT_PASSWORD", "root"))
            .build();
    container.start();
    return container;
  }

  private Catalog createMySQLCatalog() {
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put(
        "jdbc-url", mySQLContainer.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true");
    catalogProperties.put("jdbc-driver", "com.mysql.cj.jdbc.Driver");
    catalogProperties.put("jdbc-user", mySQLContainer.getUsername());
    catalogProperties.put("jdbc-password", mySQLContainer.getPassword());
    return metalake.createCatalog(
        CATALOG_NAME, Catalog.Type.RELATIONAL, "jdbc-mysql", "comment", catalogProperties);
  }

  private Table createTable(Catalog catalog, String schemaName) {
    Column[] columns = {Column.of("column", Types.StringType.get(), "")};
    return catalog
        .asTableCatalog()
        .createTable(NameIdentifier.of(schemaName, TABLE_NAME), columns, "", emptyMap());
  }

  private SearchEntityDTO querySingleTable() throws Exception {
    List<SearchEntitiesDTO> result = searchClient.search(TABLE_NAME, METALAKE_NAME);
    SearchEntitiesDTO tables = getSearchEntitiesDTOByType(result, TABLE);
    Assertions.assertNotNull(tables, "No table matched " + TABLE_NAME);
    Assertions.assertEquals(1, tables.getEntities().size());
    return tables.getEntities().get(0);
  }

  private void createIndexTemplates() throws Exception {
    Path scriptPath = resolveTemplateScriptPath(Paths.get(System.getProperty("user.dir")));
    File workDir = scriptPath.getParent().getParent().toFile();
    Process process =
        new ProcessBuilder(
                "/bin/bash",
                scriptPath.toString(),
                INDEX_TEMPLATE_VERSION,
                openSearchContainer.getOpenSearchUrl(),
                OpenSearchContainer.DEFAULT_USERNAME,
                OpenSearchContainer.DEFAULT_PASSWORD)
            .directory(workDir)
            .redirectErrorStream(true)
            .start();

    try {
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }

      Assertions.assertEquals(0, process.waitFor(), "Failed to create index templates: " + output);
    } finally {
      process.destroy();
    }
  }

  private Path resolveTemplateScriptPath(Path userDir) {
    Path current = userDir;
    while (current != null) {
      Path candidate = current.resolve("bin/opensearch/create_indices_template.sh.template");
      if (Files.exists(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Cannot find create_indices_template.sh.template from user.dir=" + userDir);
  }
}
