/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.config;

import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

public class SearchConfig extends Config {
  public static final String GRAVITINO_SEARCH_STORAGE_IMPL =
      "gravitino.datastrato.search.storage.impl";
  public static final String GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE =
      "gravitino.datastrato.search.maxTaskQueueSize";
  public static final String GRAVITINO_SEARCH_MAX_BACKGROUND_THREAD =
      "gravitino.datastrato.search.maxBackgroundThreads";
  public static final String GRAVITINO_SEARCH_MAX_BACK_OFF_TIME =
      "gravitino.datastrato.search.maxBackoffMs";
  public static final String GRAVITINO_SEARCH_SYNC_BATCH_SIZE =
      "gravitino.datastrato.search.syncBatchSize";

  // In memory implementation in this PR is only for testing purpose.
  public static final String GRAVITINO_SEARCH_STORAGE_IMPL_MEMORY = "memory";
  public static final String GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH = "opensearch";

  public static final int DEFAULT_MAX_TASK_QUEUE_SIZE = 100;
  public static final int DEFAULT_MAX_BACKGROUND_THREAD = 10;
  public static final int DEFAULT_SYNC_BATCH_SIZE = 100;
  public static final int DEFAULT_MAX_BACK_OFF_MS = 100;

  public static final ConfigEntry<String> ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL =
      new ConfigBuilder(GRAVITINO_SEARCH_STORAGE_IMPL)
          .doc("The implementation of the search storage engine")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .createWithDefault(GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH);

  public static final ConfigEntry<Integer> ENTITY_GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE =
      new ConfigBuilder(GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE)
          .doc("The maximum number of tasks in the syncing search data task queue")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_MAX_TASK_QUEUE_SIZE);
  public static final ConfigEntry<Integer> ENTITY_GRAVITINO_SEARCH_MAX_BACKGROUND_THREAD =
      new ConfigBuilder(GRAVITINO_SEARCH_MAX_BACKGROUND_THREAD)
          .doc("The maximum number of background threads for syncing search data")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .checkValue(v -> v > 1, "The number of threads for search service must be greater than 1")
          .createWithDefault(DEFAULT_MAX_BACKGROUND_THREAD);
  public static final ConfigEntry<Integer> ENTITY_GRAVITINO_SEARCH_MAX_BACK_OFF_MS =
      new ConfigBuilder(GRAVITINO_SEARCH_MAX_BACK_OFF_TIME)
          .doc("The maximum back off time for syncing search data task thread per one bach")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_MAX_BACK_OFF_MS);
  public static final ConfigEntry<Integer> ENTITY_GRAVITINO_SEARCH_SYNC_BATCH_SIZE =
      new ConfigBuilder(GRAVITINO_SEARCH_SYNC_BATCH_SIZE)
          .doc("The maximum number of records to be synced in one batch")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_SYNC_BATCH_SIZE);

  public SearchConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }

  public String getStorageImpl() {
    return get(ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL);
  }

  public int getMaxTaskQueueSize() {
    return get(ENTITY_GRAVITINO_SEARCH_MAX_TASK_QUEUE_SIZE);
  }

  public int getMaxBackgroundThread() {
    return get(ENTITY_GRAVITINO_SEARCH_MAX_BACKGROUND_THREAD);
  }

  public int getMaxBackoffMs() {
    return get(ENTITY_GRAVITINO_SEARCH_MAX_BACK_OFF_MS);
  }

  public int getSyncBatchSize() {
    return get(ENTITY_GRAVITINO_SEARCH_SYNC_BATCH_SIZE);
  }
}
