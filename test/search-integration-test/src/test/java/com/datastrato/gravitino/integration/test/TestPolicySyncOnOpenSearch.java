/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static java.util.Collections.emptyMap;
import static org.apache.gravitino.Entity.EntityType.CATALOG;
import static org.apache.gravitino.Entity.EntityType.POLICY;
import static org.apache.gravitino.Entity.EntityType.SCHEMA;
import static org.apache.gravitino.Entity.EntityType.TABLE;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchPolicyEntityDTO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyChange;
import org.apache.gravitino.policy.PolicyContents;
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

/** Verifies policy definition and association events end to end with the OpenSearch v2 bundle. */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestPolicySyncOnOpenSearch extends BaseIT {

  private static final String METALAKE_NAME = "test_policy_metalake";
  private static final String CATALOG_NAME = "test_policy_catalog";
  private static final String SCHEMA_NAME = "test_policy_schema";
  private static final String TABLE_NAME = "test_policy_table";
  private static final String POLICY_NAME = "retention_policy";
  private static final String UPDATED_POLICY_COMMENT = "updated retention policy";

  private OpenSearchContainer openSearchContainer;
  private MySQLContainer mySQLContainer;
  private GravitinoMetalake metalake;
  private SearchClient searchClient;
  private Table table;

  /**
   * Starts the OpenSearch and MySQL containers, points the Gravitino server at them and provisions
   * the index templates the server requires before it comes up.
   *
   * @throws Exception If the containers or the server fail to start.
   */
  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    openSearchContainer = createOpenSearchContainer();
    OpenSearchIndexTemplates.create(openSearchContainer, OpenSearchIndexTemplates.CURRENT_VERSION);
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

    // Creation events are synchronized asynchronously. Wait at each hierarchy level so a late
    // parent sync cannot overwrite a child entity or its subsequent policy association.
    Catalog catalog = createMySQLCatalog();
    awaitEntityIndexed(CATALOG_NAME, CATALOG);
    Schema schema = catalog.asSchemas().createSchema(SCHEMA_NAME, "", emptyMap());
    awaitEntityIndexed(SCHEMA_NAME, SCHEMA);
    table = createTable(catalog, schema.name());
    awaitEntityIndexed(TABLE_NAME, TABLE);

    metalake.createPolicy(
        POLICY_NAME,
        Policy.BuiltInType.CUSTOM.policyType(),
        "retention policy",
        true,
        PolicyContents.custom(
            ImmutableMap.of("retentionDays", 30),
            ImmutableSet.of(MetadataObject.Type.TABLE),
            ImmutableMap.of("owner", "governance")));
    table.supportsPolicies().associatePolicies(new String[] {POLICY_NAME}, null);
  }

  /**
   * Stops the Gravitino server and the containers started for this test.
   *
   * @throws IOException If the server cannot be stopped.
   * @throws InterruptedException If stopping the server is interrupted.
   */
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
  void testPolicyDefinitionAndAssociationEventsStayInSync() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              SearchPolicyEntityDTO policy = querySinglePolicy(POLICY_NAME);
              Assertions.assertEquals(
                  Policy.BuiltInType.CUSTOM.policyType(), policy.getPolicyType());
              Assertions.assertTrue(policy.isEnabled());
              Assertions.assertTrue(policy.getContent().contains("retentionDays"));

              SearchEntityDTO table = querySingleTable();
              Assertions.assertNotNull(table.getPolicyNames());
              Assertions.assertEquals(
                  ImmutableSet.of(POLICY_NAME), ImmutableSet.copyOf(table.getPolicyNames()));

              SearchEntitiesDTO tablesByPolicy =
                  getSearchEntitiesDTOByType(
                      searchClient.search("policy_name:" + POLICY_NAME, METALAKE_NAME), TABLE);
              Assertions.assertNotNull(tablesByPolicy);
              Assertions.assertEquals(1, tablesByPolicy.getEntities().size());
            });

    // Removing the association has to drop the policy from the table document.
    table.supportsPolicies().associatePolicies(null, new String[] {POLICY_NAME});
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              SearchEntityDTO disassociated = querySingleTable();
              Assertions.assertTrue(
                  disassociated.getPolicyNames() == null
                      || disassociated.getPolicyNames().isEmpty());
            });

    // Put it back, the rest of the test covers what deleting an associated policy does.
    table.supportsPolicies().associatePolicies(new String[] {POLICY_NAME}, null);
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                Assertions.assertEquals(
                    ImmutableSet.of(POLICY_NAME),
                    ImmutableSet.copyOf(querySingleTable().getPolicyNames())));

    metalake.disablePolicy(POLICY_NAME);
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> Assertions.assertFalse(querySinglePolicy(POLICY_NAME).isEnabled()));

    metalake.enablePolicy(POLICY_NAME);
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> Assertions.assertTrue(querySinglePolicy(POLICY_NAME).isEnabled()));

    metalake.alterPolicy(POLICY_NAME, PolicyChange.updateComment(UPDATED_POLICY_COMMENT));
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                Assertions.assertEquals(
                    UPDATED_POLICY_COMMENT, querySinglePolicy(POLICY_NAME).getEntityComment()));

    Assertions.assertTrue(metalake.deletePolicy(POLICY_NAME));
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              List<SearchEntitiesDTO> result = searchClient.search(POLICY_NAME, METALAKE_NAME);
              SearchEntitiesDTO policies = getSearchEntitiesDTOByType(result, POLICY);
              Assertions.assertTrue(policies == null || policies.getEntities().isEmpty());

              SearchEntityDTO table = querySingleTable();
              Assertions.assertTrue(
                  table.getPolicyNames() == null || table.getPolicyNames().isEmpty());
            });
  }

  private OpenSearchContainer createOpenSearchContainer() {
    OpenSearchContainer container =
        new OpenSearchContainer(
            "ci-opensearch-policy-sync",
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

  private SearchPolicyEntityDTO querySinglePolicy(String keyword) throws Exception {
    List<SearchEntitiesDTO> result = searchClient.search(keyword, METALAKE_NAME);
    SearchEntitiesDTO policies = getSearchEntitiesDTOByType(result, POLICY);
    Assertions.assertNotNull(policies, "No policy matched " + keyword);
    Assertions.assertEquals(1, policies.getEntities().size());
    return (SearchPolicyEntityDTO) policies.getEntities().get(0);
  }

  private void awaitEntityIndexed(String keyword, EntityType entityType) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              List<SearchEntitiesDTO> result = searchClient.search(keyword, METALAKE_NAME);
              SearchEntitiesDTO entities = getSearchEntitiesDTOByType(result, entityType);
              Assertions.assertNotNull(entities, "No " + entityType + " matched " + keyword);
              Assertions.assertEquals(1, entities.getEntities().size());
            });
  }

  private SearchEntityDTO querySingleTable() throws Exception {
    List<SearchEntitiesDTO> result = searchClient.search(TABLE_NAME, METALAKE_NAME);
    SearchEntitiesDTO tables = getSearchEntitiesDTOByType(result, TABLE);
    Assertions.assertNotNull(tables, "No table matched " + TABLE_NAME);
    Assertions.assertEquals(1, tables.getEntities().size());
    return tables.getEntities().get(0);
  }
}
