/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.config;

import static java.util.Collections.emptyMap;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSearchConfig {

  @Test
  public void testDefaultConfig() {
    Map<String, String> properties = emptyMap();
    // Create an instance of OpenSearchConfig
    SearchConfig config = new SearchConfig(properties);
    Assertions.assertEquals(
        SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH, config.getStorageImpl());
    Assertions.assertEquals(
        SearchConfig.DEFAULT_MAX_BACKGROUND_THREAD, config.getMaxBackgroundThread());
    Assertions.assertEquals(SearchConfig.DEFAULT_MAX_BACK_OFF_MS, config.getMaxBackoffMs());
    Assertions.assertEquals(SearchConfig.DEFAULT_MAX_TASK_QUEUE_SIZE, config.getMaxTaskQueueSize());
    Assertions.assertEquals(SearchConfig.DEFAULT_SYNC_BATCH_SIZE, config.getSyncBatchSize());
  }

  @Test
  public void testConfig() {
    Map<String, String> properties =
        ImmutableMap.of(
            SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL, "memory",
            SearchConfig.GRAVITINO_SEARCH_MAX_BACK_OFF_TIME, "999",
            SearchConfig.GRAVITINO_SEARCH_MAX_BACKGROUND_THREAD, "9",
            SearchConfig.GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE, "99",
            SearchConfig.GRAVITINO_SEARCH_SYNC_BATCH_SIZE, "98");
    SearchConfig config = new SearchConfig(properties);
    Assertions.assertEquals("memory", config.getStorageImpl());
    Assertions.assertEquals(9, config.getMaxBackgroundThread());
    Assertions.assertEquals(999, config.getMaxBackoffMs());
    Assertions.assertEquals(99, config.getMaxTaskQueueSize());
    Assertions.assertEquals(98, config.getSyncBatchSize());
  }
}
