/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import static org.apache.gravitino.Configs.GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.junit.jupiter.api.Test;

class TestCatalogConnectionTestGarbageCollector {

  @Test
  void testCollectAndCleanDeletesAllBatches() {
    ConnectionTestStore store = mock(ConnectionTestStore.class);
    when(store.deleteOrphanedTestResults(GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT))
        .thenReturn(100, 2, 0);

    try (CatalogConnectionTestGarbageCollector collector =
        new CatalogConnectionTestGarbageCollector(store, config())) {
      collector.collectAndClean();
    }

    verify(store, times(3)).deleteOrphanedTestResults(GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT);
  }

  @Test
  void testCollectAndCleanSuppressesStorageFailure() {
    ConnectionTestStore store = mock(ConnectionTestStore.class);
    when(store.deleteOrphanedTestResults(GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT))
        .thenThrow(new RuntimeException("storage unavailable"));

    try (CatalogConnectionTestGarbageCollector collector =
        new CatalogConnectionTestGarbageCollector(store, config())) {
      assertDoesNotThrow(collector::collectAndClean);
    }
  }

  private Config config() {
    Config config = mock(Config.class);
    when(config.get(Configs.STORE_DELETE_AFTER_TIME)).thenReturn(TimeUnit.MINUTES.toMillis(100));
    return config;
  }
}
