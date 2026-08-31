/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.LOG;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchPolicyEntityDTO;
import com.datastrato.gravitino.search.dto.SearchTableEntityDTO;
import com.datastrato.gravitino.search.dto.SearchViewEntityDTO;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchPolicyEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.datastrato.gravitino.search.store.WriteContext;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestOpenSearchStorage {
  private OpenSearchStorage storage;
  private OpenSearchContainer container;

  @BeforeAll
  public void initTest() throws IllegalAccessException {
    this.container =
        new OpenSearchContainer(
            "ci-opensearch",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                DEFAULT_PASSWORD));
    container.start();

    // initialize index template
    boolean result = initIndexTemplate();
    if (!result) {
      throw new RuntimeException("Failed to initialize index template");
    }

    this.storage = new OpenSearchStorage();
    OpenSearchConfig config =
        new OpenSearchConfig(
            ImmutableMap.of(
                OpenSearchConfig.OPEN_SEARCH_URL_KEY,
                container.getOpenSearchUrl(),
                OpenSearchConfig.OPEN_SEARCH_USERNAME_KEY,
                OpenSearchContainer.DEFAULT_USERNAME,
                OpenSearchConfig.OPEN_SEARCH_PASSWORD_KEY,
                DEFAULT_PASSWORD));
    this.storage.initialize(config);
  }

  public boolean initIndexTemplate() {
    try {
      String userDir = System.getProperty("user.dir");
      // Script was moved from search/bin/ to root bin/opensearch/
      String binDir = Paths.get(userDir, "..", "bin").normalize().toString();
      String scriptPath = binDir + "/opensearch/create_indices_template.sh.template";
      String[] command = {
        "/bin/bash",
        scriptPath,
        "v2",
        container.getOpenSearchUrl(),
        OpenSearchContainer.DEFAULT_USERNAME,
        DEFAULT_PASSWORD
      };

      ProcessBuilder builder = new ProcessBuilder(command);
      // Script uses JSON_DIR="./opensearch/$VERSION", must run from bin/ directory
      builder.directory(new File(binDir));
      builder.redirectErrorStream(true);
      Process process = builder.start();

      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      int exitCode = process.waitFor();
      LOG.info("Initialization index template output: {}", sb);
      return exitCode == 0;
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize index template", e);
    }
  }

  @Test
  void testSynchronizeMetadata() throws Exception {
    try {
      SearchEntityPO tablePO1 =
          SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
              .withEntityId(100)
              .withEntityName("table1")
              .withFullQualifiedName("c1.s1.table1")
              .withEntityType(Entity.EntityType.TABLE)
              .withMetalake("test")
              .withCatalogName("c1")
              .withEntityComment("demo table")
              .withPolicyNames(ImmutableList.of("retention_policy"))
              .withColumns(
                  ImmutableList.of(
                      SearchTableEntityPO.SearchColumn.builder()
                          .withColumnName("f1")
                          .withColumnComment("test column 1")
                          .build()))
              .build();
      storage.write(ImmutableList.of(tablePO1), true);

      List<SearchEntitiesDTO> result =
          storage.search("test", null, null, ImmutableList.of(), 10, 0);
      SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, Entity.EntityType.TABLE);
      Assertions.assertNotNull(dto);
      Assertions.assertEquals(1, dto.getTotalSize());
      SearchTableEntityDTO tableDTO = (SearchTableEntityDTO) dto.getEntities().get(0);
      Assertions.assertEquals(tableDTO.getCatalogName(), tablePO1.getCatalogName());
      Assertions.assertEquals(tableDTO.getFullQualifiedName(), tablePO1.getFullQualifiedName());

      SearchEntityPO schemaPO =
          SearchEntityPO.SearchEntityPOBuilder.builder()
              .withEntityId(200)
              .withEntityName("schema1")
              .withFullQualifiedName("c1.s1")
              .withEntityType(Entity.EntityType.SCHEMA)
              .withMetalake("test")
              .withCatalogName("c1")
              .withEntityComment("demo schema")
              .build();
      storage.write(ImmutableList.of(schemaPO), true);
      result = storage.search("test", null, null, ImmutableList.of(), 10, 0);
      dto = getSearchEntitiesDTOByType(result, Entity.EntityType.SCHEMA);
      Assertions.assertNotNull(dto);
      Assertions.assertEquals(1, dto.getTotalSize());
      SearchEntityDTO schemaDTO = dto.getEntities().get(0);
      Assertions.assertEquals(tableDTO.getCatalogName(), tablePO1.getCatalogName());
      Assertions.assertEquals(schemaDTO.getFullQualifiedName(), schemaPO.getFullQualifiedName());

      SearchCatalogEntityPO catalogEntityPO =
          SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.builder()
              .withEntityId(300)
              .withEntityName("catalog1")
              .withFullQualifiedName("c1")
              .withEntityType(Entity.EntityType.CATALOG)
              .withMetalake("test")
              .withCatalogName("c1")
              .withEntityComment("demo catalog")
              .withType(Catalog.Type.RELATIONAL)
              .withProvider("hive")
              .build();
      storage.write(ImmutableList.of(catalogEntityPO), true);
      result = storage.search("test", null, null, ImmutableList.of(), 10, 0);
      dto = getSearchEntitiesDTOByType(result, Entity.EntityType.CATALOG);
      Assertions.assertNotNull(dto);
      Assertions.assertEquals(1, dto.getTotalSize());
      SearchEntityDTO catalogDTO = dto.getEntities().get(0);
      Assertions.assertEquals(catalogDTO.getCatalogName(), catalogEntityPO.getCatalogName());
      Assertions.assertEquals(
          catalogDTO.getFullQualifiedName(), catalogEntityPO.getFullQualifiedName());

      SearchEntityPO tagPO =
          SearchEntityPO.SearchEntityPOBuilder.builder()
              .withEntityId(400)
              .withEntityName("sensitive")
              .withFullQualifiedName("sensitive")
              .withEntityType(Entity.EntityType.TAG)
              .withMetalake("test")
              .withEntityComment("business classification")
              .withEntityProperties(
                  ImmutableList.of(new SearchEntityPO.PropertyPO("level", "critical")))
              .build();
      storage.write(ImmutableList.of(tagPO), true);

      assertTagFound("sensitive");
      assertTagFound("classification");
      assertTagFound("critical");

      SearchPolicyEntityPO policyPO =
          SearchPolicyEntityPO.Builder.builder()
              .withEntityId(500)
              .withEntityName("retention_policy")
              .withFullQualifiedName("retention_policy")
              .withEntityType(Entity.EntityType.POLICY)
              .withMetalake("test")
              .withEntityComment("retains critical records")
              .withPolicyType("custom")
              .withEnabled(true)
              .withContent("{\"retentionDays\":30}")
              .build();
      storage.write(ImmutableList.of(policyPO), true);

      assertPolicyFound("retention_policy");
      assertPolicyFound("records");
      assertPolicyFound("retentionDays");

      result = storage.search("test", "retention_policy", null, ImmutableList.of(), 10, 0);
      Assertions.assertEquals(
          1, getSearchEntitiesDTOByType(result, Entity.EntityType.TABLE).getTotalSize());

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testSynchronizeAndQueryView() {
    SearchViewEntityPO viewPO =
        SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
            .withEntityId(400)
            .withEntityName("daily_orders")
            .withFullQualifiedName("c1.s1.daily_orders")
            .withEntityType(Entity.EntityType.VIEW)
            .withMetalake("view-metalake")
            .withCatalogName("c1")
            .withEntityComment("orders aggregated by day")
            .withEntityProperties(
                ImmutableList.of(new SearchEntityPO.PropertyPO("refresh-mode", "incremental")))
            .withColumns(
                ImmutableList.of(
                    SearchTableEntityPO.SearchColumn.builder()
                        .withColumnName("order_day")
                        .withColumnComment("the order day")
                        .build()))
            .build();
    storage.write(ImmutableList.of(viewPO), true);

    List<SearchEntitiesDTO> result =
        storage.search("view-metalake", null, null, ImmutableList.of(), 10, 0);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, Entity.EntityType.VIEW);
    Assertions.assertNotNull(dto);
    Assertions.assertEquals(1, dto.getTotalSize());

    SearchViewEntityDTO viewDTO = (SearchViewEntityDTO) dto.getEntities().get(0);
    Assertions.assertEquals(viewPO.getFullQualifiedName(), viewDTO.getFullQualifiedName());
    Assertions.assertEquals("order_day", viewDTO.getColumns().get(0).getColumnName());

    // The view must be reachable by its name, comment, a column name and a property.
    assertViewFound("daily_orders");
    assertViewFound("aggregated");
    assertViewFound("order_day");
    assertViewFound("refresh-mode");
    assertViewFound("incremental");
  }

  @Test
  void testRebuildTransactionPreservesExistingAndNewEntityTypes() {
    String metalake = "upgrade-test";
    SearchTableEntityPO existingTable =
        SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
            .withEntityId(600)
            .withEntityName("orders")
            .withFullQualifiedName("catalog.schema.orders")
            .withEntityType(Entity.EntityType.TABLE)
            .withMetalake(metalake)
            .withCatalogName("catalog")
            .withColumns(
                ImmutableList.of(
                    SearchTableEntityPO.SearchColumn.builder().withColumnName("id").build()))
            .build();
    storage.write(ImmutableList.of(existingTable), true);

    long transactionId = storage.beginTransaction(metalake);
    SearchTableEntityPO rebuiltTable =
        SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
            .withEntityId(600)
            .withEntityName("orders")
            .withFullQualifiedName("catalog.schema.orders")
            .withEntityType(Entity.EntityType.TABLE)
            .withMetalake(metalake)
            .withCatalogName("catalog")
            .withPolicyNames(ImmutableList.of("retention_policy"))
            .withColumns(
                ImmutableList.of(
                    SearchTableEntityPO.SearchColumn.builder().withColumnName("id").build()))
            .build();
    SearchEntityPO newTag =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(601)
            .withEntityName("sensitive")
            .withFullQualifiedName("sensitive")
            .withEntityType(Entity.EntityType.TAG)
            .withMetalake(metalake)
            .build();
    storage.write(
        ImmutableList.of(rebuiltTable, newTag),
        WriteContext.builder().withTransactionId(transactionId).build());
    storage.commit(transactionId);

    assertEntityFound(metalake, "retention_policy", Entity.EntityType.TABLE);
    assertEntityFound(metalake, "sensitive", Entity.EntityType.TAG);
  }

  private void assertViewFound(String keyword) {
    SearchEntitiesDTO dto =
        getSearchEntitiesDTOByType(
            storage.search("view-metalake", keyword, null, ImmutableList.of(), 10, 0),
            Entity.EntityType.VIEW);
    Assertions.assertNotNull(dto, "No view matched the keyword " + keyword);
    Assertions.assertEquals(1, dto.getTotalSize(), "Unexpected hits for the keyword " + keyword);
  }

  private void assertEntityFound(String metalake, String keyword, Entity.EntityType entityType) {
    SearchEntitiesDTO dto =
        getSearchEntitiesDTOByType(
            storage.search(metalake, keyword, null, ImmutableList.of(), 10, 0), entityType);
    Assertions.assertNotNull(dto, "No " + entityType + " matched the keyword " + keyword);
    Assertions.assertEquals(1, dto.getTotalSize(), "Unexpected hits for the keyword " + keyword);
  }

  private void assertTagFound(String keyword) {
    List<SearchEntitiesDTO> result =
        storage.search("test", keyword, null, ImmutableList.of(), 10, 0);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, Entity.EntityType.TAG);
    Assertions.assertNotNull(dto, "No tag matched keyword " + keyword);
    Assertions.assertEquals(1, dto.getTotalSize());
  }

  private void assertPolicyFound(String keyword) {
    List<SearchEntitiesDTO> result =
        storage.search("test", keyword, null, ImmutableList.of(), 10, 0);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, Entity.EntityType.POLICY);
    Assertions.assertNotNull(dto, "No policy matched keyword " + keyword);
    Assertions.assertEquals(1, dto.getTotalSize());
    SearchPolicyEntityDTO policy = (SearchPolicyEntityDTO) dto.getEntities().get(0);
    Assertions.assertEquals("custom", policy.getPolicyType());
    Assertions.assertTrue(policy.isEnabled());
  }
}
