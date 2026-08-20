/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.container.HiveContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test verifying that Iceberg REST events (create/drop schema, create/alter/rename/drop
 * table) are correctly propagated into OpenSearch via {@code IcebergEventHandler} and {@code
 * DataDiscoveryListener}.
 *
 * <p>Event flow under test:
 *
 * <pre>
 *   Iceberg REST client (HTTP)
 *       → IcebergRestServer (auxiliary service, isolated ClassLoader)
 *       → IcebergNamespaceEventDispatcher / IcebergTableEventDispatcher
 *       → EventListenerManager → DataDiscoveryListener
 *       → IcebergEventHandler (reflection-based; zero iceberg runtime deps in search module)
 *       → SearchService → OpenSearch
 * </pre>
 *
 * <p>Key constraint being tested: {@code search} module has NO {@code org.apache.iceberg.*} types
 * on its runtime classpath, so all Iceberg event handling relies on the class-name walk +
 * reflection in {@code IcebergEventHandler}. The test validates that this mechanism works
 * end-to-end for all meaningful event types.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergSearchIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergSearchIT.class);

  // Gravitino resources
  private static final String METALAKE_NAME = "test_iceberg_search";
  private static final String CATALOG_NAME = "iceberg_catalog";

  // Iceberg namespace (schema) names
  private static final String SCHEMA_ALPHA = "alpha";
  private static final String SCHEMA_BETA = "beta";

  // Table names used across scenarios
  private static final String TABLE_ORDERS = "orders";
  private static final String TABLE_ORDERS_V2 = "orders_v2";

  // The bundle the server expects: the view template only exists from v2 on, and
  // OpenSearchStorage#checkIndexTemplate refuses to start without a template per indexed entity
  // type.
  private static final String TEMPLATE_VERSION = "v2";

  // Default Gravitino HTTP port (must match gravitino.conf.template default).
  // Used to wire gravitino.iceberg-rest.gravitino-uri before the server starts.
  private static final int GRAVITINO_DEFAULT_PORT = 8090;

  private OpenSearchContainer openSearchContainer;
  private CloseableHttpClient httpClient;
  private SearchClient searchClient;

  // Base URI for Iceberg REST v1, scoped to CATALOG_NAME.
  // Example: http://localhost:9001/iceberg/v1/iceberg_catalog
  private String icebergCatalogUri;

  private GravitinoMetalake metalake;

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  @BeforeAll
  public void startIntegrationTest() throws Exception {
    // 1. Enable the Iceberg REST auxiliary service inside MiniGravitino.
    //    By default BaseIT sets ignoreIcebergAuxRestService = true.
    this.ignoreIcebergAuxRestService = false;

    // 2. Start OpenSearch container.
    openSearchContainer =
        new OpenSearchContainer(
            "ci-opensearch",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                OpenSearchContainer.DEFAULT_PASSWORD));
    openSearchContainer.start();
    initOpenSearchIndexTemplate();

    // 3. Start Hive container (provides Hive metastore + HDFS for Iceberg catalog backend).
    containerSuite.startHiveContainer(
        ImmutableMap.of(HiveContainer.HIVE_RUNTIME_VERSION, HiveContainer.HIVE2));
    prepareHdfsWarehouse();

    String previousHadoopUserName = System.getProperty("HADOOP_USER_NAME");
    try {
      // Allow the Iceberg REST service (running in our JVM alongside Gravitino) to write to HDFS.
      // The warehouse dir has been chmod'd 777 inside the container; any Hadoop user may write.
      System.setProperty("HADOOP_USER_NAME", "gravitino");
      // 4. Register custom configs that override or extend the gravitino.conf.template defaults.
      Map<String, String> configs = new HashMap<>();
      // Explicitly enable the search event listener; mirrors the DataDiscoveryTest pattern.
      configs.put("gravitino.eventListener.names", "search");
      configs.put(
          "gravitino.datastrato.search.storage.impl", "opensearch"); // template default is "memory"
      configs.put(
          "gravitino.datastrato.search.opensearch.url", openSearchContainer.getOpenSearchUrl());
      configs.put(
          "gravitino.datastrato.search.opensearch.username", OpenSearchContainer.DEFAULT_USERNAME);
      configs.put(
          "gravitino.datastrato.search.opensearch.password", OpenSearchContainer.DEFAULT_PASSWORD);
      // Switch Iceberg REST from the default static memory backend to dynamic-config-provider,
      // which fetches catalog config from the Gravitino server at request time.
      configs.put("gravitino.iceberg-rest.catalog-config-provider", "dynamic-config-provider");
      // Must match the Gravitino server's actual HTTP port (template default: 8090).
      configs.put(
          "gravitino.iceberg-rest.gravitino-uri", "http://127.0.0.1:" + GRAVITINO_DEFAULT_PORT);
      configs.put("gravitino.iceberg-rest.gravitino-metalake", METALAKE_NAME);
      // Short cache TTL so catalog config changes are picked up quickly during the test.
      configs.put("gravitino.iceberg-rest.catalog-cache-eviction-interval-ms", "1000");
      registerCustomConfigs(configs);
      // 5. Start Gravitino (includes embedded Iceberg REST auxiliary service).
      super.startIntegrationTest();
      // 6. Initialise HTTP clients.
      httpClient = HttpClients.createDefault();
      searchClient = new SearchClient("http://localhost:" + getGravitinoServerPort());
      // getIcebergRestServiceUri() reads gravitino.iceberg-rest.host / httpPort from serverConfig.
      // Default: http://0.0.0.0:9001/iceberg/ → reachable as http://localhost:9001/iceberg/
      String icebergRestBase = getIcebergRestServiceUri().replace("0.0.0.0", "localhost");
      icebergCatalogUri = icebergRestBase + "v1/" + CATALOG_NAME;
      // 7. Create the Gravitino metalake and Iceberg catalog.
      createMetalake();
      createIcebergCatalog();
    } finally {
      if (previousHadoopUserName != null) {
        System.setProperty("HADOOP_USER_NAME", previousHadoopUserName);
      } else {
        System.clearProperty("HADOOP_USER_NAME");
      }
    }
  }

  @AfterAll
  public void stopIntegrationTest() throws IOException, InterruptedException {
    try {
      if (client != null && metalake != null) {
        client.dropMetalake(METALAKE_NAME, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop metalake '{}' during cleanup", METALAKE_NAME, e);
    }
    if (searchClient != null) searchClient.close();
    if (httpClient != null) httpClient.close();
    if (openSearchContainer != null) openSearchContainer.stop();
    super.stopIntegrationTest();
  }

  // -------------------------------------------------------------------------
  // Test
  // -------------------------------------------------------------------------

  /**
   * Runs all Iceberg event → OpenSearch sync scenarios sequentially. Scenarios share state (e.g.
   * the table created in step 3 is renamed in step 5), so they are intentionally kept in a single
   * {@code @Test} method to guarantee execution order and avoid cross-test pollution.
   *
   * <p>Scenarios:
   *
   * <ol>
   *   <li>Create schema → schema indexed
   *   <li>Create second schema (needed for cross-schema rename later)
   *   <li>Create table → table indexed
   *   <li>Alter table (add column) → table still indexed (re-synced)
   *   <li>Rename table same-schema → old name gone, new name indexed
   *   <li>Rename table cross-schema → new name indexed under target schema
   *   <li>Drop table → table gone
   *   <li>Drop schema alpha (cascade) → schema gone
   *   <li>Drop schema beta (cascade) → schema gone
   * </ol>
   */
  @Test
  void testIcebergSearchSync() throws Exception {
    // ---- Scenario 1: Create schema alpha ----
    LOG.info("Scenario 1: create schema '{}'", SCHEMA_ALPHA);
    icebergCreateNamespace(SCHEMA_ALPHA);
    awaitEntityIndexed(SCHEMA_ALPHA, EntityType.SCHEMA, 1);
    LOG.info("PASS: schema '{}' indexed", SCHEMA_ALPHA);

    // ---- Scenario 2: Create schema beta (needed for cross-schema rename) ----
    LOG.info("Scenario 2: create schema '{}'", SCHEMA_BETA);
    icebergCreateNamespace(SCHEMA_BETA);
    awaitEntityIndexed(SCHEMA_BETA, EntityType.SCHEMA, 1);
    LOG.info("PASS: schema '{}' indexed", SCHEMA_BETA);

    // ---- Scenario 3: Create table alpha.orders ----
    LOG.info("Scenario 3: create table '{}.{}'", SCHEMA_ALPHA, TABLE_ORDERS);
    icebergCreateTable(SCHEMA_ALPHA, TABLE_ORDERS);
    awaitEntityIndexed(TABLE_ORDERS, EntityType.TABLE, 1);
    LOG.info("PASS: table '{}' indexed", TABLE_ORDERS);

    // ---- Scenario 4: Alter table metadata — set property ----
    LOG.info(
        "Scenario 4: alter table '{}.{}' (set property search_it=enabled)",
        SCHEMA_ALPHA,
        TABLE_ORDERS);
    icebergAlterTableSetProperty(SCHEMA_ALPHA, TABLE_ORDERS, "search_it", "enabled");
    // Table must still be present in the index (re-synced after ALTER_TABLE event).
    awaitEntityIndexed(TABLE_ORDERS, EntityType.TABLE, 1);
    LOG.info("PASS: table '{}' still indexed after alter", TABLE_ORDERS);

    // ---- Scenario 5: Rename table — same schema: alpha.orders → alpha.orders_v2 ----
    LOG.info(
        "Scenario 5: same-schema rename '{}.{}' → '{}.{}'",
        SCHEMA_ALPHA,
        TABLE_ORDERS,
        SCHEMA_ALPHA,
        TABLE_ORDERS_V2);
    icebergRenameTable(SCHEMA_ALPHA, TABLE_ORDERS, SCHEMA_ALPHA, TABLE_ORDERS_V2);
    awaitEntityGone(TABLE_ORDERS, EntityType.TABLE);
    awaitEntityIndexed(TABLE_ORDERS_V2, EntityType.TABLE, 1);
    LOG.info(
        "PASS: '{}' removed and '{}' indexed after same-schema rename",
        TABLE_ORDERS,
        TABLE_ORDERS_V2);

    // ---- Scenario 6: Rename table — cross-schema: alpha.orders_v2 → beta.orders_v2 ----
    LOG.info(
        "Scenario 6: cross-schema rename '{}.{}' → '{}.{}'",
        SCHEMA_ALPHA,
        TABLE_ORDERS_V2,
        SCHEMA_BETA,
        TABLE_ORDERS_V2);
    icebergRenameTable(SCHEMA_ALPHA, TABLE_ORDERS_V2, SCHEMA_BETA, TABLE_ORDERS_V2);
    awaitTableIndexedUnderSchema(TABLE_ORDERS_V2, SCHEMA_BETA);
    LOG.info(
        "PASS: '{}' now indexed under schema '{}' after cross-schema rename",
        TABLE_ORDERS_V2,
        SCHEMA_BETA);

    // ---- Scenario 7: Drop table beta.orders_v2 ----
    LOG.info("Scenario 7: drop table '{}.{}'", SCHEMA_BETA, TABLE_ORDERS_V2);
    icebergDropTable(SCHEMA_BETA, TABLE_ORDERS_V2);
    awaitEntityGone(TABLE_ORDERS_V2, EntityType.TABLE);
    LOG.info("PASS: table '{}' removed from index", TABLE_ORDERS_V2);

    // ---- Scenario 8: Drop schema alpha (cascade) ----
    LOG.info("Scenario 8: drop schema '{}' (cascade)", SCHEMA_ALPHA);
    icebergDropNamespace(SCHEMA_ALPHA);
    awaitEntityGone(SCHEMA_ALPHA, EntityType.SCHEMA);
    LOG.info("PASS: schema '{}' removed from index", SCHEMA_ALPHA);

    // ---- Scenario 9: Drop schema beta (cascade) ----
    LOG.info("Scenario 9: drop schema '{}' (cascade)", SCHEMA_BETA);
    icebergDropNamespace(SCHEMA_BETA);
    awaitEntityGone(SCHEMA_BETA, EntityType.SCHEMA);
    LOG.info("PASS: schema '{}' removed from index", SCHEMA_BETA);
  }

  // -------------------------------------------------------------------------
  // Iceberg REST helpers
  // -------------------------------------------------------------------------

  private void icebergCreateNamespace(String namespaceName) throws IOException {
    String body = String.format("{\"namespace\":[\"%s\"],\"properties\":{}}", namespaceName);
    icebergPost(icebergCatalogUri + "/namespaces", body);
  }

  private void icebergDropNamespace(String namespaceName) throws IOException {
    icebergDelete(icebergCatalogUri + "/namespaces/" + namespaceName);
  }

  private void icebergCreateTable(String namespaceName, String tableName) throws IOException {
    String body =
        String.format(
            "{"
                + "\"name\":\"%s\","
                + "\"schema\":{"
                + "  \"type\":\"struct\",\"schema-id\":0,"
                + "  \"fields\":["
                + "    {\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"},"
                + "    {\"id\":2,\"name\":\"amount\",\"required\":false,\"type\":\"double\"}"
                + "  ]"
                + "},"
                + "\"partition-spec\":{\"spec-id\":0,\"fields\":[]},"
                + "\"write-order\":{\"order-id\":0,\"fields\":[]},"
                + "\"stage-create\":false,"
                + "\"properties\":{}"
                + "}",
            tableName);
    icebergPost(icebergCatalogUri + "/namespaces/" + namespaceName + "/tables", body);
  }

  private void icebergAlterTableSetProperty(
      String namespaceName, String tableName, String propertyKey, String propertyValue)
      throws IOException {
    String body =
        String.format(
            "{\"requirements\":[],\"updates\":[{\"action\":\"set-properties\","
                + "\"updates\":{\"%s\":\"%s\"}}]}",
            propertyKey, propertyValue);
    icebergPost(icebergCatalogUri + "/namespaces/" + namespaceName + "/tables/" + tableName, body);
  }

  private void icebergRenameTable(
      String srcSchema, String srcTable, String dstSchema, String dstTable) throws IOException {
    String body =
        String.format(
            "{\"source\":{\"namespace\":[\"%s\"],\"name\":\"%s\"},"
                + "\"destination\":{\"namespace\":[\"%s\"],\"name\":\"%s\"}}",
            srcSchema, srcTable, dstSchema, dstTable);
    icebergPost(icebergCatalogUri + "/tables/rename", body);
  }

  private void icebergDropTable(String namespaceName, String tableName) throws IOException {
    icebergDelete(icebergCatalogUri + "/namespaces/" + namespaceName + "/tables/" + tableName);
  }

  private String icebergPost(String uri, String body) throws IOException {
    HttpPost post = new HttpPost(uri);
    post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
    return httpClient.execute(
        post,
        response -> {
          int code = response.getCode();
          String responseBody = readResponseBody(response.getEntity());
          if (code >= 400) {
            throw new RuntimeException(
                "Iceberg REST POST " + uri + " failed [HTTP " + code + "]: " + responseBody);
          }
          return responseBody;
        });
  }

  private void icebergDelete(String uri) throws IOException {
    HttpDelete delete = new HttpDelete(uri);
    httpClient.execute(
        delete,
        response -> {
          int code = response.getCode();
          HttpEntity entity = response.getEntity();
          if (code >= 400) {
            String responseBody = readResponseBody(entity);
            throw new RuntimeException(
                "Iceberg REST DELETE " + uri + " failed [HTTP " + code + "]: " + responseBody);
          }
          if (entity != null) {
            EntityUtils.consume(entity);
          }
          return null;
        });
  }

  private String readResponseBody(HttpEntity entity) throws IOException {
    if (entity == null) {
      return "";
    }
    try {
      return EntityUtils.toString(entity);
    } catch (ParseException e) {
      throw new IOException("Failed to parse response body", e);
    }
  }

  // -------------------------------------------------------------------------
  // Search / Awaitility helpers
  // -------------------------------------------------------------------------

  /**
   * Polls OpenSearch until {@code expectedCount} entities of {@code entityType} matching {@code
   * keyword} are indexed.
   */
  private void awaitEntityIndexed(String keyword, EntityType entityType, int expectedCount) {
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<SearchEntitiesDTO> dtos = searchClient.search(keyword, METALAKE_NAME);
              SearchEntitiesDTO byType = getSearchEntitiesDTOByType(dtos, entityType);
              Assertions.assertNotNull(
                  byType,
                  () ->
                      "Expected a "
                          + entityType
                          + " result for keyword '"
                          + keyword
                          + "' but got: "
                          + dtos);
              Assertions.assertEquals(
                  expectedCount,
                  byType.getEntities().size(),
                  () ->
                      "Expected "
                          + expectedCount
                          + " "
                          + entityType
                          + " entities for '"
                          + keyword
                          + "', actual: "
                          + byType.getEntities().size());
            });
  }

  /** Polls OpenSearch until no entity of {@code entityType} matches {@code keyword}. */
  private void awaitEntityGone(String keyword, EntityType entityType) {
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<SearchEntitiesDTO> dtos = searchClient.search(keyword, METALAKE_NAME);
              SearchEntitiesDTO byType = getSearchEntitiesDTOByType(dtos, entityType);
              long exactNameMatches =
                  byType == null
                      ? 0
                      : byType.getEntities().stream()
                          .filter(entity -> keyword.equals(entity.getEntityName()))
                          .count();
              Assertions.assertTrue(
                  exactNameMatches == 0,
                  () ->
                      "Expected "
                          + entityType
                          + " '"
                          + keyword
                          + "' to be gone from the index, but found: "
                          + (byType == null ? "null" : byType.getEntities()));
            });
  }

  /**
   * Polls OpenSearch until {@code tableName} appears under the expected schema, verified by
   * inspecting {@code fullQualifiedName} (e.g. {@code iceberg_catalog.beta.orders_v2}).
   *
   * <p>Used for the cross-schema rename scenario where the table name is unchanged but the schema
   * portion of the fully-qualified name must change.
   */
  private void awaitTableIndexedUnderSchema(String tableName, String expectedSchema) {
    String expectedFqnFragment = CATALOG_NAME + "." + expectedSchema + "." + tableName;
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<SearchEntitiesDTO> dtos = searchClient.search(tableName, METALAKE_NAME);
              SearchEntitiesDTO byType = getSearchEntitiesDTOByType(dtos, EntityType.TABLE);
              Assertions.assertNotNull(
                  byType,
                  "Expected TABLE result for '" + tableName + "' after cross-schema rename");
              Assertions.assertEquals(1, byType.getEntities().size());
              SearchEntityDTO entity = byType.getEntities().get(0);
              Assertions.assertEquals(
                  expectedFqnFragment,
                  entity.getFullQualifiedName(),
                  () ->
                      "Expected fullQualifiedName '"
                          + expectedFqnFragment
                          + "' but got: "
                          + entity.getFullQualifiedName());
            });
  }

  // -------------------------------------------------------------------------
  // Setup helpers
  // -------------------------------------------------------------------------

  /**
   * Creates the HDFS warehouse directory inside the Hive container and grants world-write
   * permission so the Iceberg REST service (running in this JVM with user "gravitino") can create
   * table directories.
   */
  private void prepareHdfsWarehouse() {
    containerSuite
        .getHiveContainer()
        .executeInContainer(
            "bash",
            "-c",
            "hdfs dfs -mkdir -p /user/hive/warehouse/iceberg_catalog 2>/dev/null; "
                + "hdfs dfs -chmod -R 777 /user/hive/warehouse/ 2>/dev/null; echo done");
  }

  private void createMetalake() {
    client.createMetalake(METALAKE_NAME, "comment", Collections.emptyMap());
    metalake = client.loadMetalake(METALAKE_NAME);
  }

  /**
   * Creates a Gravitino Iceberg catalog backed by the Hive metastore + HDFS running in the Hive
   * container. The {@code dynamic-config-provider} on the Iceberg REST service reads this catalog's
   * properties at request time and uses them to initialise the Iceberg {@code HiveCatalog}.
   */
  private void createIcebergCatalog() {
    String hiveIp = containerSuite.getHiveContainer().getContainerIpAddress();
    Map<String, String> props = new HashMap<>();
    props.put("catalog-backend", "hive");
    props.put("uri", "thrift://" + hiveIp + ":" + HiveContainer.HIVE_METASTORE_PORT);
    props.put(
        "warehouse",
        "hdfs://"
            + hiveIp
            + ":"
            + HiveContainer.HDFS_DEFAULTFS_PORT
            + "/user/hive/warehouse/iceberg_catalog");
    metalake.createCatalog(
        CATALOG_NAME, Catalog.Type.RELATIONAL, "lakehouse-iceberg", "comment", props);
  }

  private void initOpenSearchIndexTemplate() {
    try {
      Path userDir = Paths.get(System.getProperty("user.dir"));
      Path scriptPath = resolveTemplateScriptPath(userDir);
      File workDir = scriptPath.getParent().getParent().toFile();

      String[] command = {
        "/bin/bash",
        scriptPath.toString(),
        TEMPLATE_VERSION,
        openSearchContainer.getOpenSearchUrl(),
        OpenSearchContainer.DEFAULT_USERNAME,
        OpenSearchContainer.DEFAULT_PASSWORD
      };

      ProcessBuilder builder = new ProcessBuilder(command);
      builder.directory(workDir);
      builder.redirectErrorStream(true);
      Process process = builder.start();

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new RuntimeException(
            "Failed to initialize OpenSearch index templates, exitCode="
                + exitCode
                + ", output="
                + output);
      }
      LOG.info("Initialized OpenSearch index templates successfully: {}", output);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize OpenSearch index templates", e);
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

    throw new RuntimeException(
        "Cannot find create_indices_template.sh.template from user.dir=" + userDir);
  }
}
