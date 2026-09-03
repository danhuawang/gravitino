/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog;

import static com.datastrato.gravitino.TestFilesetPropertiesMetadata.TEST_FILESET_HIDDEN_KEY;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

import com.datastrato.gravitino.storage.InMemoryEntityStore;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.HiddenPropertyMaskUtils;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;

public class TestDatastratoOperationDispatcher {
  public static EntityStore entityStore;

  protected static final IdGenerator idGenerator = new RandomIdGenerator();

  protected static final String metalake = "metalake";

  protected static final String catalog = "catalog";

  protected static CatalogManager catalogManager;

  private static Config config;

  @BeforeAll
  public static void setUp() throws IOException, IllegalAccessException {
    config = new Config(false) {};
    config.set(Configs.CATALOG_LOAD_ISOLATED, false);

    entityStore = spy(new InMemoryEntityStore());
    entityStore.initialize(config);

    BaseMetalake metalakeEntity =
        BaseMetalake.builder()
            .withId(1L)
            .withName(metalake)
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .withVersion(SchemaVersion.V_0_1)
            .build();
    entityStore.put(metalakeEntity, true);

    catalogManager = new CatalogManager(config, entityStore, idGenerator);

    NameIdentifier ident = NameIdentifier.of(metalake, catalog);
    Map<String, String> props = ImmutableMap.of("key1", "value1", "key2", "value2");

    config.set(TREE_LOCK_MAX_NODE_IN_MEMORY, 100000L);
    config.set(TREE_LOCK_MIN_NODE_IN_MEMORY, 1000L);
    config.set(TREE_LOCK_CLEAN_INTERVAL, 36000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);

    catalogManager.createCatalog(ident, Catalog.Type.RELATIONAL, "test", "comment", props);
  }

  @AfterAll
  public static void tearDown() throws IOException {
    if (entityStore != null) {
      entityStore.close();
      entityStore = null;
    }

    if (catalogManager != null) {
      catalogManager.close();
      catalogManager = null;
    }
  }

  @BeforeEach
  public void beforeStart() {
    reset(entityStore);
  }

  void testProperties(Map<String, String> expectedProps, Map<String, String> testProps) {
    expectedProps.forEach(
        (k, v) -> {
          Assertions.assertEquals(v, testProps.get(k));
        });
    Assertions.assertFalse(
        testProps.containsKey(StringIdentifier.ID_KEY),
        "`gravitino.identifier` should be omitted from API responses");
    Assertions.assertTrue(
        !testProps.containsKey(TEST_FILESET_HIDDEN_KEY)
            || HiddenPropertyMaskUtils.MASKED_VALUE.equals(testProps.get(TEST_FILESET_HIDDEN_KEY)));
  }

  void testPropertyException(Executable operation, String... errorMessage) {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, operation);
    for (String msg : errorMessage) {
      Assertions.assertTrue(exception.getMessage().contains(msg));
    }
  }
}
