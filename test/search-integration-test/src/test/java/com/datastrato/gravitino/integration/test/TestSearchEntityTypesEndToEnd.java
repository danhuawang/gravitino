/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.integration.test;

import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchPolicyEntityDTO;
import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.function.FunctionCatalog;
import org.apache.gravitino.function.FunctionChange;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionDefinitions;
import org.apache.gravitino.function.FunctionImpl;
import org.apache.gravitino.function.FunctionImpls;
import org.apache.gravitino.function.FunctionParam;
import org.apache.gravitino.function.FunctionParams;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.integration.test.container.HiveContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyChange;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Dialects;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.rel.ViewChange;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.TagChange;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies synchronization and filtered search for the seven newly indexed entity types against a
 * deployed Gravitino server, Hive Metastore, and OpenSearch.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestSearchEntityTypesEndToEnd extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(TestSearchEntityTypesEndToEnd.class);

  private static final String METALAKE_NAME = "search_entity_types_metalake";
  private static final String CATALOG_NAME = "search_entity_types_catalog";
  private static final String SCHEMA_NAME = "search_entity_types_schema";
  private static final String SEARCH_MARKER = "e2e1371";
  private static final String VIEW_NAME = SEARCH_MARKER + "view";
  private static final String RENAMED_VIEW_NAME = SEARCH_MARKER + "viewaltered";
  private static final String FUNCTION_NAME = SEARCH_MARKER + "function";
  private static final String USER_NAME = SEARCH_MARKER + "user";
  private static final String GROUP_NAME = SEARCH_MARKER + "group";
  private static final String TAG_NAME = SEARCH_MARKER + "tag";
  private static final String ROLE_NAME = SEARCH_MARKER + "role";
  private static final String POLICY_NAME = SEARCH_MARKER + "policy";
  private static final String FUNCTION_ALTERED_COMMENT = "funcalt";
  private static final String TAG_ALTERED_COMMENT = "tagalt";
  private static final String POLICY_ALTERED_COMMENT = "policyalt";

  private OpenSearchContainer openSearchContainer;
  private GravitinoMetalake metalake;
  private SearchClient searchClient;
  private FunctionCatalog functionCatalog;
  private ViewCatalog viewCatalog;

  /**
   * Starts all external services and creates the Hive catalog used by View and Function entities.
   *
   * @throws Exception If a container or the Gravitino server cannot start.
   */
  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    openSearchContainer = createOpenSearchContainer();
    OpenSearchIndexTemplates.create(openSearchContainer, OpenSearchIndexTemplates.CURRENT_VERSION);
    containerSuite.startHiveContainer(
        ImmutableMap.of(HiveContainer.HIVE_RUNTIME_VERSION, HiveContainer.HIVE3));

    Map<String, String> configs = new HashMap<>();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), String.valueOf(true));
    configs.put(Configs.SERVICE_ADMINS.getKey(), AuthConstants.ANONYMOUS_USER);
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

    metalake =
        client.createMetalake(METALAKE_NAME, "Search entity type E2E", Collections.emptyMap());
    String hmsUri =
        String.format(
            "thrift://%s:%d",
            "127.0.0.1",
            containerSuite.getHiveContainer().getMappedPort(HiveContainer.HIVE_METASTORE_PORT));
    Catalog catalog =
        metalake.createCatalog(
            CATALOG_NAME,
            Catalog.Type.RELATIONAL,
            "hive",
            "Search entity type E2E catalog",
            ImmutableMap.of("metastore.uris", hmsUri));
    catalog
        .asSchemas()
        .createSchema(SCHEMA_NAME, "Search entity type E2E schema", Collections.emptyMap());
    functionCatalog = catalog.asFunctionCatalog();
    viewCatalog = catalog.asViewCatalog();
  }

  /**
   * Drops test metadata and stops the server and OpenSearch container.
   *
   * @throws IOException If the server cannot stop.
   * @throws InterruptedException If shutdown is interrupted.
   */
  @AfterAll
  @Override
  public void stopIntegrationTest() throws IOException, InterruptedException {
    try {
      if (client != null && client.metalakeExists(METALAKE_NAME)) {
        client.dropMetalake(METALAKE_NAME, true);
      }
    } catch (Exception e) {
      LOG.warn("Failed to drop metalake '{}' during cleanup", METALAKE_NAME, e);
    }

    try {
      if (searchClient != null) {
        searchClient.close();
      }
    } finally {
      try {
        super.stopIntegrationTest();
      } finally {
        if (openSearchContainer != null) {
          openSearchContainer.stop();
        }
      }
    }
  }

  @Test
  void testEntityLifecycleAndFilteredSearch() throws Exception {
    createEntities();

    Map<EntityType, String> allEntities = expectedEntities(VIEW_NAME);
    awaitSearch().untilAsserted(() -> assertSearchResult(SEARCH_MARKER, allEntities));
    awaitSearch()
        .untilAsserted(
            () ->
                assertSearchResult(
                    SEARCH_MARKER + " entity_type:view",
                    ImmutableMap.of(EntityType.VIEW, VIEW_NAME)));
    awaitSearch()
        .untilAsserted(
            () ->
                assertSearchResult(
                    SEARCH_MARKER + " entity_type:view,function,tag",
                    ImmutableMap.of(
                        EntityType.VIEW,
                        VIEW_NAME,
                        EntityType.FUNCTION,
                        FUNCTION_NAME,
                        EntityType.TAG,
                        TAG_NAME)));

    long originalRoleUpdateTime = querySingleEntity(SEARCH_MARKER, EntityType.ROLE).getUpdateTime();
    long originalUserUpdateTime = querySingleEntity(SEARCH_MARKER, EntityType.USER).getUpdateTime();
    alterEntities();

    awaitSearch()
        .untilAsserted(
            () -> {
              assertEntityAbsent(VIEW_NAME, EntityType.VIEW);
              SearchEntityDTO renamedView = querySingleEntity(RENAMED_VIEW_NAME, EntityType.VIEW);
              Assertions.assertEquals(RENAMED_VIEW_NAME, renamedView.getEntityName());

              SearchEntityDTO function = querySingleEntity(SEARCH_MARKER, EntityType.FUNCTION);
              Assertions.assertEquals(FUNCTION_ALTERED_COMMENT, function.getEntityComment());

              SearchEntityDTO tag = querySingleEntity(SEARCH_MARKER, EntityType.TAG);
              Assertions.assertEquals(TAG_ALTERED_COMMENT, tag.getEntityComment());

              SearchPolicyEntityDTO policy =
                  (SearchPolicyEntityDTO) querySingleEntity(SEARCH_MARKER, EntityType.POLICY);
              Assertions.assertEquals(POLICY_ALTERED_COMMENT, policy.getEntityComment());
              Assertions.assertFalse(policy.isEnabled());

              Assertions.assertTrue(
                  querySingleEntity(SEARCH_MARKER, EntityType.ROLE).getUpdateTime()
                      > originalRoleUpdateTime);
              Assertions.assertTrue(
                  querySingleEntity(SEARCH_MARKER, EntityType.USER).getUpdateTime()
                      > originalUserUpdateTime);
            });

    awaitSearch()
        .untilAsserted(
            () -> assertSearchResult(SEARCH_MARKER, expectedEntities(RENAMED_VIEW_NAME)));

    dropEntities();
    awaitSearch()
        .untilAsserted(
            () ->
                Assertions.assertTrue(searchClient.search(SEARCH_MARKER, METALAKE_NAME).isEmpty()));
  }

  private void createEntities() {
    NameIdentifier viewIdentifier = NameIdentifier.of(SCHEMA_NAME, VIEW_NAME);
    viewCatalog.createView(
        viewIdentifier,
        "Initial view comment",
        new Column[] {Column.of("value", Types.IntegerType.get(), "View value")},
        new SQLRepresentation[] {
          SQLRepresentation.builder()
              .withDialect(Dialects.HIVE)
              .withSql("SELECT 1 AS value")
              .build()
        },
        null,
        null,
        Collections.emptyMap());

    FunctionParam param = FunctionParams.of("value", Types.IntegerType.get());
    FunctionImpl implementation =
        FunctionImpls.ofSql(FunctionImpl.RuntimeType.SPARK, "SELECT value + 1");
    FunctionDefinition definition =
        FunctionDefinitions.of(
            new FunctionParam[] {param},
            Types.IntegerType.get(),
            new FunctionImpl[] {implementation});
    functionCatalog.registerFunction(
        NameIdentifier.of(SCHEMA_NAME, FUNCTION_NAME),
        "Initial function comment",
        FunctionType.SCALAR,
        true,
        new FunctionDefinition[] {definition});

    metalake.addUser(USER_NAME);
    metalake.addGroup(GROUP_NAME);
    metalake.createTag(TAG_NAME, "Initial tag comment", Collections.emptyMap());
    metalake.createRole(ROLE_NAME, Collections.emptyMap(), Collections.emptyList());
    metalake.createPolicy(
        POLICY_NAME,
        Policy.BuiltInType.CUSTOM.policyType(),
        "Initial policy comment",
        true,
        PolicyContents.custom(
            ImmutableMap.of("retentionDays", 30),
            ImmutableSet.of(MetadataObject.Type.VIEW),
            ImmutableMap.of("owner", "search-e2e")));
  }

  private void alterEntities() {
    viewCatalog.alterView(
        NameIdentifier.of(SCHEMA_NAME, VIEW_NAME), ViewChange.rename(RENAMED_VIEW_NAME));
    functionCatalog.alterFunction(
        NameIdentifier.of(SCHEMA_NAME, FUNCTION_NAME),
        FunctionChange.updateComment(FUNCTION_ALTERED_COMMENT));
    metalake.alterTag(TAG_NAME, TagChange.updateComment(TAG_ALTERED_COMMENT));
    metalake.disablePolicy(POLICY_NAME);
    metalake.alterPolicy(POLICY_NAME, PolicyChange.updateComment(POLICY_ALTERED_COMMENT));

    MetadataObject metalakeObject =
        MetadataObjects.of(null, METALAKE_NAME, MetadataObject.Type.METALAKE);
    metalake.grantPrivilegesToRole(
        ROLE_NAME, metalakeObject, ImmutableSet.of(Privileges.CreateCatalog.allow()));
    metalake.grantRolesToUser(ImmutableList.of(ROLE_NAME), USER_NAME);
  }

  private void dropEntities() {
    Assertions.assertTrue(viewCatalog.dropView(NameIdentifier.of(SCHEMA_NAME, RENAMED_VIEW_NAME)));
    Assertions.assertTrue(
        functionCatalog.dropFunction(NameIdentifier.of(SCHEMA_NAME, FUNCTION_NAME)));
    Assertions.assertTrue(metalake.removeUser(USER_NAME));
    Assertions.assertTrue(metalake.removeGroup(GROUP_NAME));
    Assertions.assertTrue(metalake.deleteTag(TAG_NAME));
    Assertions.assertTrue(metalake.deleteRole(ROLE_NAME));
    Assertions.assertTrue(metalake.deletePolicy(POLICY_NAME));
  }

  private void assertSearchResult(String query, Map<EntityType, String> expected) throws Exception {
    List<SearchEntitiesDTO> result = searchClient.search(query, METALAKE_NAME);
    Assertions.assertEquals(
        expected.keySet(),
        result.stream().map(SearchEntitiesDTO::getType).collect(ImmutableSet.toImmutableSet()));
    expected.forEach(
        (type, name) -> {
          SearchEntitiesDTO group = getSearchEntitiesDTOByType(result, type);
          Assertions.assertNotNull(group, "No search group returned for " + type);
          Assertions.assertEquals(
              1, group.getEntities().size(), "Unexpected result count for " + type);
          Assertions.assertEquals(name, group.getEntities().get(0).getEntityName());
        });
  }

  private SearchEntityDTO querySingleEntity(String keyword, EntityType type) throws Exception {
    List<SearchEntitiesDTO> result =
        searchClient.search(
            keyword + " entity_type:" + type.name().toLowerCase(Locale.ROOT), METALAKE_NAME);
    SearchEntitiesDTO group = getSearchEntitiesDTOByType(result, type);
    Assertions.assertNotNull(group, "No " + type + " entity matched " + keyword);
    Assertions.assertEquals(1, group.getEntities().size());
    return group.getEntities().get(0);
  }

  private void assertEntityAbsent(String keyword, EntityType type) throws Exception {
    SearchEntitiesDTO group =
        getSearchEntitiesDTOByType(
            searchClient.search(
                "entity_name:" + keyword + " entity_type:" + type.name().toLowerCase(Locale.ROOT),
                METALAKE_NAME),
            type);
    Assertions.assertTrue(group == null || group.getEntities().isEmpty());
  }

  private Map<EntityType, String> expectedEntities(String viewName) {
    return ImmutableMap.<EntityType, String>builder()
        .put(EntityType.VIEW, viewName)
        .put(EntityType.FUNCTION, FUNCTION_NAME)
        .put(EntityType.USER, USER_NAME)
        .put(EntityType.GROUP, GROUP_NAME)
        .put(EntityType.TAG, TAG_NAME)
        .put(EntityType.ROLE, ROLE_NAME)
        .put(EntityType.POLICY, POLICY_NAME)
        .build();
  }

  private ConditionFactory awaitSearch() {
    return Awaitility.await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(1));
  }

  private OpenSearchContainer createOpenSearchContainer() {
    OpenSearchContainer container =
        new OpenSearchContainer(
            "ci-opensearch-entity-types",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                OpenSearchContainer.DEFAULT_PASSWORD));
    container.start();
    return container;
  }
}
