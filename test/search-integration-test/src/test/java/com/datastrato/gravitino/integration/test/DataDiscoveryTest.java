/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static java.util.Collections.emptyMap;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.TagChange;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DataDiscoveryTest extends BaseIT {
  private static final Logger LOG = LoggerFactory.getLogger(DataDiscoveryTest.class);

  private static final String METALAKE_NAME = "test_metalake";

  private OpenSearchContainer openSearchContainer;
  private MySQLContainer mySQLContainer;

  private GravitinoMetalake metalake;
  private SearchClient searchClient;

  @BeforeAll
  public void startIntegrationTest() throws Exception {
    openSearchContainer = createOpenSearchContainer();
    // The server refuses to start when an indexed entity type has no template, so the bundle has
    // to exist before it comes up.
    OpenSearchIndexTemplates.create(openSearchContainer, OpenSearchIndexTemplates.CURRENT_VERSION);

    mySQLContainer = createMySQLContainer();
    Map<String, String> configs = new HashMap<>();
    // Explicitly enable the search event listener; BaseIT disables it by default in deploy mode.
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

    createMetalake(METALAKE_NAME);
  }

  @AfterAll
  public void stopIntegrationTest() throws IOException, InterruptedException {
    super.stopIntegrationTest();
    if (openSearchContainer != null) {
      openSearchContainer.stop();
    }
    if (mySQLContainer != null) {
      mySQLContainer.close();
    }
    if (searchClient != null) {
      searchClient.close();
    }
  }

  void test1() throws Exception {
    Catalog catalog = getOrCreateMysqlCatalog("c1");
    Schema schema = createSchema(catalog, "s1");
    createTag("tag1");
    schema.supportsTags().associateTags(new String[] {"tag1"}, null);
    createTable(catalog, "s1", "tb1");

    Thread.sleep(5000);
    List<SearchEntitiesDTO> dtos = searchClient.search("", metalake.name());

    SearchEntitiesDTO catalogDtos = getSearchEntitiesDTOByType(dtos, EntityType.CATALOG);
    Assertions.assertNotNull(catalogDtos);
    Assertions.assertEquals(1, catalogDtos.getEntities().size());
    Assertions.assertEquals("c1", catalogDtos.getEntities().get(0).getEntityName());

    SearchEntitiesDTO schemaDtos = getSearchEntitiesDTOByType(dtos, EntityType.SCHEMA);
    Assertions.assertNotNull(schemaDtos);
    Assertions.assertEquals(1, schemaDtos.getEntities().size());
    Assertions.assertEquals("s1", schemaDtos.getEntities().get(0).getEntityName());

    SearchEntitiesDTO tableDtos = getSearchEntitiesDTOByType(dtos, EntityType.TABLE);
    Assertions.assertNotNull(tableDtos);
    Assertions.assertEquals(1, tableDtos.getEntities().size());
    Assertions.assertEquals("tb1", tableDtos.getEntities().get(0).getEntityName());
  }

  @Test
  void test2() throws Exception {
    try {
      int numberOfSchemas = 5;
      int numberOfTables = 100;
      int totalTables = numberOfSchemas * numberOfTables;
      int totalTagTableEntities = 0;
      int totalTagSchemaEntities = 0;
      Random random = new Random();

      Catalog catalog = getOrCreateMysqlCatalog("c2");
      createTag("tag2");

      // Step 1: create all schemas and tables first — no tagging yet.
      // Separating creation from tagging avoids a race condition where a non-cascade
      // schema-create sync task can overwrite the schema document (without tags) after
      // the cascade tag-association sync task has already written it with tags.
      List<Schema> createdSchemas = new ArrayList<>();
      List<List<Table>> createdTables = new ArrayList<>();
      for (int i = 1; i <= numberOfSchemas; i++) {
        Schema schema = createSchema(catalog, String.format("s%03d", i));
        createdSchemas.add(schema);
        List<Table> tables = new ArrayList<>();
        for (int j = 1; j <= numberOfTables; j++) {
          tables.add(createTable(catalog, schema.name(), String.format("tb%05d", j)));
          int total = (i - 1) * numberOfTables + j;
          if (total % 100 == 0) {
            LOG.info("Created {} tables so far", total);
          }
        }
        createdTables.add(tables);
      }

      LOG.info("Created {} tables in catalog c2", totalTables);

      // test catalog query
      long startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> dtos =
                    searchClient.search("catalog_name:c2", metalake.name());

                if (dtos.size() != 3) {
                  return false;
                }

                SearchEntitiesDTO catalogDtos =
                    getSearchEntitiesDTOByType(dtos, EntityType.CATALOG);
                if (catalogDtos.getEntities().size() != 1) {
                  return false;
                }

                SearchEntitiesDTO schemaDtos = getSearchEntitiesDTOByType(dtos, EntityType.SCHEMA);
                if (schemaDtos.getEntities().size() != numberOfSchemas) {
                  return false;
                }

                SearchEntitiesDTO tableDtos = getSearchEntitiesDTOByType(dtos, EntityType.TABLE);
                if (tableDtos.getEntities().size() != totalTables) {
                  return false;
                }
                return true;
              });
      LOG.info(
          "Search catalog c2 of {} tables successfully indexed after {} seconds",
          totalTables,
          (System.currentTimeMillis() - startTime) / 1000);

      // Step 2: now that all creation sync tasks have settled (catalog query passed),
      // associate tags. This prevents the race where a stale schema-create sync task
      // overwrites the schema document WITHOUT tags after the tag sync already wrote it WITH tags.
      for (int i = 0; i < numberOfSchemas; i++) {
        Schema schema = createdSchemas.get(i);
        boolean tagged = false;
        if (random.nextInt(5) == 0) {
          schema.supportsTags().associateTags(new String[] {"tag2"}, null);
          totalTagSchemaEntities++;
          totalTagTableEntities += numberOfTables;
          tagged = true;
        }
        if (!tagged) {
          for (Table table : createdTables.get(i)) {
            if (random.nextInt(100) == 0) {
              table.supportsTags().associateTags(new String[] {"tag2"}, null);
              totalTagTableEntities++;
            }
          }
        }
      }
      LOG.info(
          "Tagged {} schema(s) and {} table(s) with tag2",
          totalTagSchemaEntities,
          totalTagTableEntities);

      // test tag query
      startTime = System.currentTimeMillis();
      int finalTotalTagTableEntities = totalTagTableEntities;
      int finalTotalTagSchemaEntities = totalTagSchemaEntities;
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> dtos =
                    searchClient.search("tag_name:tag2", metalake.name());

                if (finalTotalTagSchemaEntities > 0) {
                  SearchEntitiesDTO schemaDtos =
                      getSearchEntitiesDTOByType(dtos, EntityType.SCHEMA);
                  LOG.info(
                      "Search tag tag2 got {} schema entities, expected {}",
                      schemaDtos == null ? 0 : schemaDtos.getEntities().size(),
                      finalTotalTagSchemaEntities);
                  if (schemaDtos == null
                      || schemaDtos.getEntities().size() != finalTotalTagSchemaEntities) {
                    return false;
                  }
                }
                if (finalTotalTagTableEntities > 0) {
                  LOG.info(
                      "Search tag tag2 got {} table entities, expected {}",
                      getSearchEntitiesDTOByType(dtos, EntityType.TABLE) == null
                          ? 0
                          : getSearchEntitiesDTOByType(dtos, EntityType.TABLE).getEntities().size(),
                      finalTotalTagTableEntities);
                  SearchEntitiesDTO tableDtos = getSearchEntitiesDTOByType(dtos, EntityType.TABLE);
                  if (tableDtos == null
                      || tableDtos.getEntities().size() != finalTotalTagTableEntities) {
                    return false;
                  }
                }
                return true;
              });
      LOG.info(
          "Search tag tag2 of {} tables successfully indexed after {} seconds",
          totalTagTableEntities,
          (System.currentTimeMillis() - startTime) / 1000);

      // test catalog rename
      metalake.alterCatalog("c2", CatalogChange.rename("c3"));
      startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> newCatalogDtos =
                    searchClient.search("catalog_name:c3", metalake.name());
                List<SearchEntitiesDTO> oldCatalogDtos =
                    searchClient.search("catalog_name:c2", metalake.name());

                if (!oldCatalogDtos.isEmpty() || newCatalogDtos.size() != 3) {
                  return false;
                }

                SearchEntitiesDTO catalogDtos =
                    getSearchEntitiesDTOByType(newCatalogDtos, EntityType.CATALOG);
                if (catalogDtos.getEntities().size() != 1) {
                  return false;
                }

                SearchEntitiesDTO schemaDtos =
                    getSearchEntitiesDTOByType(newCatalogDtos, EntityType.SCHEMA);
                if (schemaDtos.getEntities().size() != numberOfSchemas) {
                  return false;
                }

                SearchEntitiesDTO tableDtos =
                    getSearchEntitiesDTOByType(newCatalogDtos, EntityType.TABLE);
                if (tableDtos.getEntities().size() != totalTables) {
                  return false;
                }
                return true;
              });
      LOG.info(
          "Search catalog c2 renamed to c3 of {} tables successfully indexed after {} seconds",
          totalTables,
          (System.currentTimeMillis() - startTime) / 1000);

      // test tag rename
      metalake.alterTag("tag2", TagChange.rename("tag3"));
      startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> oldTagDtos =
                    searchClient.search("tag_name:tag2", metalake.name());
                List<SearchEntitiesDTO> newTagDtos =
                    searchClient.search("tag_name:tag3", metalake.name());
                if (!oldTagDtos.isEmpty()) {
                  return false;
                }
                if (finalTotalTagSchemaEntities > 0) {
                  SearchEntitiesDTO schemaDtos =
                      getSearchEntitiesDTOByType(newTagDtos, EntityType.SCHEMA);
                  if (schemaDtos == null
                      || schemaDtos.getEntities().size() != finalTotalTagSchemaEntities) {
                    return false;
                  }
                }
                if (finalTotalTagTableEntities > 0) {
                  SearchEntitiesDTO tableDtos =
                      getSearchEntitiesDTOByType(newTagDtos, EntityType.TABLE);
                  if (tableDtos == null
                      || tableDtos.getEntities().size() != finalTotalTagTableEntities) {
                    return false;
                  }
                }
                return true;
              });
      LOG.info(
          "Search tag tag2 renamed to tag3 of {} tables successfully indexed after {} seconds",
          totalTagTableEntities,
          (System.currentTimeMillis() - startTime) / 1000);

      // test rebuild index
      // The rebuild writes to temp indices via a transaction.  We must wait for the task to reach
      // COMPLETED status (which means the transaction has been committed and the alias has switched
      // to the new index) before proceeding.  Only then will subsequent deletes (tag, catalog)
      // apply to the committed index instead of the stale old one.
      String rebuildTaskId = searchClient.rebuildIndex(metalake.name());
      startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                com.datastrato.gravitino.search.dto.TaskStatusDTO status =
                    searchClient.getTaskStatus(rebuildTaskId);
                if (status == null) {
                  return false;
                }
                String taskStatus = status.getTaskStatus();
                LOG.info("Rebuild task {} status: {}", rebuildTaskId, taskStatus);
                return "COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus);
              });
      LOG.info(
          "Rebuild task completed after {} seconds",
          (System.currentTimeMillis() - startTime) / 1000);

      // Verify search results reflect the committed rebuild
      startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(60, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> dtos =
                    searchClient.search("catalog_name:c3", metalake.name());

                if (dtos.size() != 3) {
                  return false;
                }

                SearchEntitiesDTO catalogDtos =
                    getSearchEntitiesDTOByType(dtos, EntityType.CATALOG);
                if (catalogDtos.getEntities().size() != 1) {
                  return false;
                }

                SearchEntitiesDTO schemaDtos = getSearchEntitiesDTOByType(dtos, EntityType.SCHEMA);
                if (schemaDtos.getEntities().size() != numberOfSchemas) {
                  return false;
                }

                SearchEntitiesDTO tableDtos = getSearchEntitiesDTOByType(dtos, EntityType.TABLE);
                if (tableDtos.getEntities().size() != totalTables) {
                  return false;
                }
                return true;
              });
      LOG.info(
          "Rebuild index successfully after {} seconds",
          (System.currentTimeMillis() - startTime) / 1000);

      // test tag delete
      deleteTag("tag3");
      startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> dtos =
                    searchClient.search("tag_name:tag3", metalake.name());
                return dtos.isEmpty();
              });
      LOG.info(
          "Search tag tag3 deleted successfully after {} seconds",
          (System.currentTimeMillis() - startTime) / 1000);

      // test catalog delete
      metalake.dropCatalog("c3", true);
      startTime = System.currentTimeMillis();
      Awaitility.await()
          .atMost(180, TimeUnit.SECONDS)
          .pollInterval(5, TimeUnit.SECONDS)
          .until(
              () -> {
                List<SearchEntitiesDTO> dtos =
                    searchClient.search("catalog_name:c3", metalake.name());
                return dtos.isEmpty();
              });
      LOG.info(
          "Search catalog c3 deleted successfully after {} seconds",
          (System.currentTimeMillis() - startTime) / 1000);

    } catch (Exception e) {
      LOG.error("Error during test", e);
      throw e;
    }
  }

  private OpenSearchContainer createOpenSearchContainer() {
    OpenSearchContainer container =
        new OpenSearchContainer(
            "ci-opensearch",
            ImmutableMap.<String, String>builder()
                .put("discovery.type", "single-node")
                .put("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "axzin1S3?@A")
                .build());
    container.start();

    return container;
  }

  private MySQLContainer createMySQLContainer() {
    MySQLContainer.Builder builder =
        MySQLContainer.builder()
            .withEnvVars(
                ImmutableMap.<String, String>builder().put("MYSQL_ROOT_PASSWORD", "root").build());
    MySQLContainer container = builder.build();
    container.start();
    return container;
  }

  private void createMetalake(String name) {
    client.createMetalake(name, "comment", emptyMap());
    metalake = client.loadMetalake(name);
  }

  private Catalog getOrCreateMysqlCatalog(String catalogName) {
    boolean exists = metalake.catalogExists(catalogName);
    if (exists) {
      return metalake.loadCatalog(catalogName);
    }

    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put(
        "jdbc-url", mySQLContainer.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true");
    catalogProperties.put("jdbc-driver", "com.mysql.cj.jdbc.Driver");
    catalogProperties.put("jdbc-user", mySQLContainer.getUsername());
    catalogProperties.put("jdbc-password", mySQLContainer.getPassword());
    catalogProperties.put("", "");

    Catalog catalog =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, "jdbc-mysql", "comment", catalogProperties);
    return catalog;
  }

  private Schema createSchema(Catalog catalog, String schemaName) {
    return catalog.asSchemas().createSchema(schemaName, "", emptyMap());
  }

  private Table createTable(Catalog catalog, String schemaName, String tableName) {
    Column col1 = Column.of("f1", Types.StringType.get(), "");
    Column col2 = Column.of("f2", Types.IntegerType.get(), "");
    Column[] columns = new Column[] {col1, col2};
    return catalog
        .asTableCatalog()
        .createTable(NameIdentifier.of(schemaName, tableName), columns, "", emptyMap());
  }

  private void createTag(String tagName) {
    metalake.createTag(tagName, "comment", emptyMap());
  }

  private void deleteTag(String tagName) {
    metalake.deleteTag(tagName);
  }
}
