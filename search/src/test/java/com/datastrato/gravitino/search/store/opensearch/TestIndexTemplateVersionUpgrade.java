/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.search.store.WriteContext;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.gravitino.Entity;
import org.apache.gravitino.integration.test.util.CommandExecutor;
import org.apache.gravitino.integration.test.util.CommandExecutor.IGNORE_ERRORS;
import org.apache.gravitino.integration.test.util.ProcessData;
import org.apache.gravitino.integration.test.util.ProcessData.TypesOfData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestIndexTemplateVersionUpgrade {

  private static final List<String> V1_PATTERNS =
      List.of(
          "catalog_entity_index",
          "fileset_entity_index",
          "model_entity_index",
          "schema_entity_index",
          "table_entity_index",
          "topic_entity_index");
  private static final List<String> V2_PATTERNS =
      List.of(
          "catalog_entity_index",
          "fileset_entity_index",
          "model_entity_index",
          "schema_entity_index",
          "table_entity_index",
          "topic_entity_index",
          "user_entity_index",
          "group_entity_index",
          "role_entity_index",
          "function_entity_index",
          "view_entity_index",
          "tag_entity_index",
          "policy_entity_index");

  private OpenSearchContainer container;

  @TempDir private Path binDirectory;

  private String lastIndexScriptOutput = "";

  @BeforeAll
  void startContainer() {
    Assumptions.assumeTrue(isJqAvailable(), "index.sh requires jq");
    container =
        new OpenSearchContainer(
            "ci-opensearch-index-template-v2",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                DEFAULT_PASSWORD));
    container.start();
  }

  @AfterAll
  void stopContainer() {
    if (container != null) {
      container.stop();
    }
  }

  @BeforeEach
  void resetCluster() throws Exception {
    curl("-X", "DELETE", container.getOpenSearchUrl() + "/*_entity_index*");
    curl(
        "-X",
        "DELETE",
        container.getOpenSearchUrl() + "/_index_template/*_entity_index_template_v*");
    stageBinDirectory();
  }

  @Test
  void testFreshInitializationUsesV2() throws Exception {
    assertEquals(0, runIndexScript("init"));

    assertTemplatesExist(V2_PATTERNS, "v2");
    assertTemplatesMissing(V1_PATTERNS, "v1");
  }

  @Test
  void testUpgradeReplacesV1WithV2() throws Exception {
    assertEquals(0, runIndexScript("init", "v1"));
    assertTemplatesExist(V1_PATTERNS, "v1");

    assertEquals(0, runIndexScript("upgrade", "v2"));

    assertTemplatesExist(V2_PATTERNS, "v2");
    assertTemplatesMissing(V1_PATTERNS, "v1");
  }

  @Test
  void testV1DataCanBeQueriedAndRebuiltAfterV2Upgrade() throws Exception {
    String metalake = "upgrade-data-test";
    SearchTableEntityPO v1Table =
        SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
            .withEntityId(100)
            .withEntityName("legacy_orders")
            .withFullQualifiedName("catalog.schema.legacy_orders")
            .withEntityType(Entity.EntityType.TABLE)
            .withMetalake(metalake)
            .withCatalogName("catalog")
            .withColumns(
                ImmutableList.of(
                    SearchTableEntityPO.SearchColumn.builder().withColumnName("order_id").build()))
            .build();

    assertEquals(0, runIndexScript("init", "v1"));
    assertTemplatesExist(V1_PATTERNS, "v1");
    createV1TableIndexAndDocument(v1Table);

    assertEquals(0, runIndexScript("upgrade", "v2"));
    assertTemplatesExist(V2_PATTERNS, "v2");
    assertTemplatesMissing(V1_PATTERNS, "v1");

    try (OpenSearchStorage storage = createStorage()) {
      SearchEntitiesDTO oldData =
          assertEntityFound(storage, metalake, "legacy_orders", Entity.EntityType.TABLE);
      assertEquals(
          v1Table.getFullQualifiedName(), oldData.getEntities().get(0).getFullQualifiedName());

      long transactionId = storage.beginTransaction(metalake);
      SearchTableEntityPO rebuiltTable =
          SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
              .withEntityId(100)
              .withEntityName("legacy_orders")
              .withFullQualifiedName("catalog.schema.legacy_orders")
              .withEntityType(Entity.EntityType.TABLE)
              .withMetalake(metalake)
              .withCatalogName("catalog")
              .withPolicyNames(ImmutableList.of("retention_policy"))
              .withColumns(
                  ImmutableList.of(
                      SearchTableEntityPO.SearchColumn.builder()
                          .withColumnName("order_id")
                          .build()))
              .build();
      SearchEntityPO newV2Tag =
          SearchEntityPO.SearchEntityPOBuilder.builder()
              .withEntityId(101)
              .withEntityName("sensitive")
              .withFullQualifiedName("sensitive")
              .withEntityType(Entity.EntityType.TAG)
              .withMetalake(metalake)
              .build();

      storage.write(
          ImmutableList.of(rebuiltTable, newV2Tag),
          WriteContext.builder().withTransactionId(transactionId).build());
      storage.commit(transactionId);

      assertEntityFound(storage, metalake, "retention_policy", Entity.EntityType.TABLE);
      assertEntityFound(storage, metalake, "sensitive", Entity.EntityType.TAG);
      assertEquals(
          404,
          curl("-X", "GET", container.getOpenSearchUrl() + "/" + v1TableIndexName(metalake)),
          "the rebuild should remove the v1 physical index");
    }
  }

  @Test
  void testFailedV2CreationPreservesV1() throws Exception {
    assertEquals(0, runIndexScript("init", "v1"));
    Files.writeString(
        binDirectory.resolve("opensearch/v2/view_entity_indices.json"),
        "invalid json",
        StandardCharsets.UTF_8);

    assertNotEquals(0, runIndexScript("upgrade", "v2"));

    assertTemplatesExist(V1_PATTERNS, "v1");
    assertTemplatesMissing(V2_PATTERNS, "v2");
  }

  @Test
  void testUpgradeRepairsAPartialV2Bundle() throws Exception {
    assertEquals(0, runIndexScript("init", "v1"));
    createV2ViewTemplate();

    assertEquals(0, runIndexScript("upgrade", "v2"));

    assertTemplatesExist(V2_PATTERNS, "v2");
    assertTemplatesMissing(V1_PATTERNS, "v1");
  }

  @Test
  void testUpgradeDeletesPreviousVersionWithoutLocalManifest() throws Exception {
    assertEquals(0, runIndexScript("init", "v1"));
    deleteRecursively(binDirectory.resolve("opensearch/v1"));

    assertEquals(0, runIndexScript("upgrade", "v2"));

    assertTemplatesExist(V2_PATTERNS, "v2");
    assertTemplatesMissing(V1_PATTERNS, "v1");
  }

  @Test
  void testPartialV2CreationIsReportedAsPartial() throws Exception {
    assertEquals(0, runIndexScript("init", "v1"));
    // Valid JSON, so it passes the pre-flight checks and is only rejected by OpenSearch itself.
    Files.writeString(
        binDirectory.resolve("opensearch/v2/view_entity_indices.json"),
        "{\"mappings\":{\"properties\":{\"entity_id\":{\"type\":\"no_such_type\"}}}}",
        StandardCharsets.UTF_8);

    assertNotEquals(0, runIndexScript("upgrade", "v2"));

    assertTrue(
        lastIndexScriptOutput.contains("only partially created"),
        "upgrade should report a partially installed bundle, but printed:\n"
            + lastIndexScriptOutput);
    assertTemplatesExist(V1_PATTERNS, "v1");
    assertTemplatesMissing(List.of("view_entity_index"), "v2");
  }

  private void deleteRecursively(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
        Files.delete(path);
      }
    }
  }

  private void stageBinDirectory() throws IOException {
    Path sourceBinDirectory = sourceBinDirectory();
    Path openSearchDirectory = binDirectory.resolve("opensearch");
    Files.createDirectories(openSearchDirectory);

    copyScript(sourceBinDirectory.resolve("index.sh.template"), binDirectory.resolve("index.sh"));
    for (String script : List.of("create_indices_template", "delete_indices_template")) {
      copyScript(
          sourceBinDirectory.resolve("opensearch").resolve(script + ".sh.template"),
          openSearchDirectory.resolve(script + ".sh"));
    }
    for (String version : List.of("v1", "v2")) {
      Path sourceVersionDirectory = sourceBinDirectory.resolve("opensearch").resolve(version);
      Path targetVersionDirectory = openSearchDirectory.resolve(version);
      Files.createDirectories(targetVersionDirectory);
      try (Stream<Path> files = Files.list(sourceVersionDirectory)) {
        for (Path file : files.toArray(Path[]::new)) {
          Files.copy(
              file,
              targetVersionDirectory.resolve(file.getFileName().toString()),
              StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private void copyScript(Path source, Path target) throws IOException {
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    assertTrue(target.toFile().setExecutable(true));
  }

  private int runIndexScript(String... arguments) throws Exception {
    String[] command = new String[arguments.length + 5];
    command[0] = "/bin/bash";
    command[1] = binDirectory.resolve("index.sh").toString();
    System.arraycopy(arguments, 0, command, 2, arguments.length);
    command[arguments.length + 2] = "--opensearch_uri=" + container.getOpenSearchUrl();
    command[arguments.length + 3] = "--username=" + DEFAULT_USERNAME;
    command[arguments.length + 4] = "--password=" + DEFAULT_PASSWORD;

    ProcessData processData =
        (ProcessData)
            CommandExecutor.executeCommandLocalHost(
                command,
                false,
                TypesOfData.PROCESS_DATA_OBJECT,
                IGNORE_ERRORS.TRUE,
                Map.of("GRAVITINO_HOME", ""));
    int exitCode = processData.getExitCodeValue();
    lastIndexScriptOutput = processData.toString();
    OpenSearchContainer.LOG.info(
        "index.sh {} exited with {}:\n{}", arguments[0], exitCode, lastIndexScriptOutput);
    return exitCode;
  }

  private void assertTemplatesExist(List<String> patterns, String version) throws Exception {
    for (String pattern : patterns) {
      String template = templateName(pattern, version);
      assertEquals(200, templateStatus(template), template + " should exist");
    }
  }

  private void assertTemplatesMissing(List<String> patterns, String version) throws Exception {
    for (String pattern : patterns) {
      String template = templateName(pattern, version);
      assertEquals(404, templateStatus(template), template + " should be absent");
    }
  }

  private int templateStatus(String template) throws Exception {
    return curl("-X", "GET", templateUrl(template));
  }

  private void createV2ViewTemplate() throws Exception {
    String mapping =
        Files.readString(
            binDirectory.resolve("opensearch/v2/view_entity_indices.json"), StandardCharsets.UTF_8);
    String request = "{\"index_patterns\":[\"*view_entity_index*\"],\"template\":" + mapping + "}";
    assertEquals(
        200,
        curl(
            "-X",
            "POST",
            "-H",
            "Content-Type: application/json",
            "--data-binary",
            request,
            templateUrl(templateName("view_entity_index", "v2"))));
  }

  private void createV1TableIndexAndDocument(SearchTableEntityPO table) throws Exception {
    String indexName = v1TableIndexName(table.getMetalake());
    String aliasName = tableIndexAliasName(table.getMetalake());
    String aliasRequest =
        String.format(
            "{\"actions\":[{\"add\":{\"index\":\"%s\",\"alias\":\"%s\",\"is_write_index\":true}}]}",
            indexName, aliasName);

    assertEquals(200, curl("-X", "PUT", container.getOpenSearchUrl() + "/" + indexName));
    assertEquals(
        200,
        curl(
            "-X",
            "POST",
            "-H",
            "Content-Type: application/json",
            "--data-binary",
            aliasRequest,
            container.getOpenSearchUrl() + "/_aliases"));
    assertEquals(
        201,
        curl(
            "-X",
            "PUT",
            "-H",
            "Content-Type: application/json",
            "--data-binary",
            SearchEntityCodec.INSTANCE.serialize(table),
            container.getOpenSearchUrl()
                + "/"
                + indexName
                + "/_doc/"
                + table.getEntityId()
                + "?refresh=true"));
  }

  private OpenSearchStorage createStorage() {
    OpenSearchStorage storage = new OpenSearchStorage();
    storage.initialize(
        new OpenSearchConfig(
            ImmutableMap.of(
                OpenSearchConfig.OPEN_SEARCH_URL_KEY,
                container.getOpenSearchUrl(),
                OpenSearchConfig.OPEN_SEARCH_USERNAME_KEY,
                DEFAULT_USERNAME,
                OpenSearchConfig.OPEN_SEARCH_PASSWORD_KEY,
                DEFAULT_PASSWORD)));
    return storage;
  }

  private SearchEntitiesDTO assertEntityFound(
      OpenSearchStorage storage, String metalake, String keyword, Entity.EntityType entityType) {
    SearchEntitiesDTO result =
        getSearchEntitiesDTOByType(
            storage.search(metalake, keyword, null, ImmutableList.of(), 10, 0), entityType);
    assertNotNull(result, "No " + entityType + " matched " + keyword);
    assertEquals(1, result.getTotalSize(), "Unexpected hits for " + keyword);
    return result;
  }

  private static String v1TableIndexName(String metalake) {
    return tableIndexAliasName(metalake) + "_0";
  }

  private static String tableIndexAliasName(String metalake) {
    return metalake + "_table_entity_index";
  }

  private String templateUrl(String template) {
    return container.getOpenSearchUrl() + "/_index_template/" + template;
  }

  private int curl(String... arguments) throws Exception {
    String[] command =
        Stream.concat(
                Stream.of(
                    "curl",
                    "-s",
                    "-k",
                    "-o",
                    "/dev/null",
                    "-w",
                    "%{http_code}",
                    "-u",
                    DEFAULT_USERNAME + ":" + DEFAULT_PASSWORD),
                Stream.of(arguments))
            .toArray(String[]::new);
    String status =
        (String)
            CommandExecutor.executeCommandLocalHost(
                command, false, TypesOfData.OUTPUT, IGNORE_ERRORS.FALSE, Map.of());
    return Integer.parseInt(status.trim());
  }

  private static String templateName(String pattern, String version) {
    return pattern + "_template_" + version;
  }

  private static Path sourceBinDirectory() {
    Path userDirectory = Paths.get(System.getProperty("user.dir"));
    Path localBin = userDirectory.resolve("bin");
    if (Files.isDirectory(localBin)) {
      return localBin;
    }
    return userDirectory.resolve("..").resolve("bin").normalize();
  }

  private static boolean isJqAvailable() {
    try {
      int exitCode =
          (Integer)
              CommandExecutor.executeCommandLocalHost(
                  new String[] {"jq", "--version"},
                  false,
                  TypesOfData.EXIT_CODE,
                  IGNORE_ERRORS.TRUE,
                  Map.of());
      return exitCode == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
