/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.dto.SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.apache.gravitino.Entity.EntityType.METALAKE;
import static org.apache.gravitino.Entity.EntityType.ROLE;
import static org.apache.gravitino.Entity.EntityType.TABLE;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.listener.RoleEventHandler;
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
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.listener.api.event.GrantPrivilegesEvent;
import org.apache.gravitino.listener.api.event.RevokePrivilegesEvent;
import org.apache.gravitino.listener.api.info.RoleInfo;
import org.apache.gravitino.meta.RoleEntity;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/** Exercises Role discovery and permission projections against a real OpenSearch instance. */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestRolePermissionSyncOnOpenSearch {
  private static final String METALAKE_NAME = "test_metalake";
  private static final String ROLE_NAME = "table_reader";
  private static final String USER_NAME = "alice";
  private static final String TABLE_FULL_NAME = "test_catalog1.test_schema1.test_table1";

  private OpenSearchContainer container;
  private MockedGravitinoService gravitinoService;
  private SearchService searchService;
  private RoleEventHandler roleEventHandler;
  private MetadataObject tableObject;
  private SecurableObject tableGrant;
  private Privilege selectTable;

  @BeforeAll
  public void initTest() throws Exception {
    container =
        new OpenSearchContainer(
            "ci-opensearch-role-permission-sync",
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
    roleEventHandler = new RoleEventHandler(searchService);
    gravitinoService.createMetalake(METALAKE_NAME);
    gravitinoService.createCatalog(NameIdentifier.of(METALAKE_NAME, "test_catalog1"));
    gravitinoService.createSchema(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1"));
    gravitinoService.createTable(
        NameIdentifier.of(METALAKE_NAME, "test_catalog1", "test_schema1", "test_table1"));
    gravitinoService.createRole(METALAKE_NAME, ROLE_NAME, ImmutableList.of());
    gravitinoService.createUser(METALAKE_NAME, USER_NAME, ImmutableList.of(ROLE_NAME));

    tableObject = MetadataObjects.parse(TABLE_FULL_NAME, MetadataObject.Type.TABLE);
    selectTable = Privileges.SelectTable.allow();
    tableGrant =
        SecurableObjects.parse(
            TABLE_FULL_NAME, MetadataObject.Type.TABLE, ImmutableList.of(selectTable));
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
  void testRoleDiscoveryAndGrantRevokePermissionProjection() {
    SyncTask task =
        searchService.synchronizeMetadata(NameIdentifier.of(METALAKE_NAME), METALAKE, true);
    awaitCompletion(task);

    SearchEntityDTO role = findEntity("reader", ROLE, ROLE_NAME);
    Assertions.assertEquals(ROLE, role.getEntityType());
    Assertions.assertNull(role.getEntityComment());
    Assertions.assertNull(role.getCatalogName());
    Assertions.assertNull(role.getFullQualifiedName());
    assertNoPermissions(findEntity("test table", TABLE, "test_table1"));

    RoleEntity grantedRole =
        gravitinoService.updateRoleSecurableObjects(
            METALAKE_NAME, ROLE_NAME, ImmutableList.of(tableGrant));
    roleEventHandler.handleEvent(
        new GrantPrivilegesEvent(
            "tester",
            METALAKE_NAME,
            new RoleInfo(grantedRole),
            ImmutableSet.of(selectTable),
            tableObject));

    awaitTablePermissions(true);

    RoleEntity revokedRole =
        gravitinoService.updateRoleSecurableObjects(METALAKE_NAME, ROLE_NAME, ImmutableList.of());
    roleEventHandler.handleEvent(
        new RevokePrivilegesEvent(
            "tester",
            METALAKE_NAME,
            new RoleInfo(revokedRole),
            tableObject,
            ImmutableSet.of(selectTable)));

    awaitTablePermissions(false);
    Assertions.assertDoesNotThrow(() -> searchService.query(METALAKE_NAME, "description", 0, 10));
  }

  private void awaitTablePermissions(boolean expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              SearchEntityDTO table = findEntity("test table", TABLE, "test_table1");
              if (expected) {
                Assertions.assertNotNull(table.getRolePermissions());
                Assertions.assertNotNull(table.getUserPermissions());
                Assertions.assertTrue(
                    table.getRolePermissions().stream()
                        .anyMatch(
                            permission ->
                                ROLE_NAME.equals(permission.getName())
                                    && "ALLOW select table".equals(permission.getPermission())));
                Assertions.assertTrue(
                    table.getUserPermissions().stream()
                        .anyMatch(
                            permission ->
                                USER_NAME.equals(permission.getName())
                                    && "ALLOW select table".equals(permission.getPermission())));
              } else {
                assertNoPermissions(table);
              }
            });
  }

  private static void assertNoPermissions(SearchEntityDTO entity) {
    Assertions.assertTrue(
        entity.getRolePermissions() == null || entity.getRolePermissions().isEmpty());
    Assertions.assertTrue(
        entity.getUserPermissions() == null || entity.getUserPermissions().isEmpty());
  }

  private SearchEntityDTO findEntity(String keyword, EntityType type, String name) {
    List<SearchEntitiesDTO> result = searchService.query(METALAKE_NAME, keyword, 0, 10);
    SearchEntitiesDTO dto = getSearchEntitiesDTOByType(result, type);
    Assertions.assertNotNull(dto, "No " + type + " matched keyword " + keyword);
    return dto.getEntities().stream()
        .filter(entity -> name.equals(entity.getEntityName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(type + " " + name + " was not returned"));
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
