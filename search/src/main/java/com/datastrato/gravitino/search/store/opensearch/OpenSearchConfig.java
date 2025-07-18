/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

public class OpenSearchConfig extends Config {

  public static final String OPEN_SEARCH_URL_KEY = "gravitino.datastrato.search.opensearch.url";
  public static final String OPEN_SEARCH_USERNAME_KEY =
      "gravitino.datastrato.search.opensearch.username";
  public static final String OPEN_SEARCH_PASSWORD_KEY =
      "gravitino.datastrato.search.opensearch.password";
  public static final String OPEN_SEARCH_WRITE_MAX_RETRY =
      "gravitino.datastrato.search.opensearch.writeMaxRetry";
  public static final String OPEN_SEARCH_RETRY_BACKOFF_MS =
      "gravitino.datastrato.search.opensearch.retryBackoffMs";
  public static final String OPEN_SEARCH_QUERY_TIMEOUT_MS =
      "gravitino.datastrato.search.opensearch.queryTimeoutMs";
  public static final String OPEN_SEARCH_MAX_QUERY_THREAD =
      "gravitino.datastrato.search.opensearch.maxQueryThread";
  public static final String OPEN_SEARCH_MAX_QUERY_QUEUE_SIZE =
      "gravitino.datastrato.search.opensearch.maxQueryQueueSize";
  public static final String OPEN_SEARCH_BACKGROUND_QUERY_TIMEOUT =
      "gravitino.datastrato.search.opensearch.backgroundQueryTimeout";

  public static final int DEFAULT_WRITE_MAX_RETRY = 3;
  public static final int DEFAULT_RETRY_BACKOFF_MS = 3000;
  public static final int DEFAULT_QUERY_TIMEOUT_MS = 30000;
  public static final int DEFAULT_MAX_QUERY_THREAD = 10;
  public static final int DEFAULT_MAX_QUERY_QUEUE_SIZE = 100;
  public static final String DEFAULT_BACKGROUND_QUERY_QUEUE_TIMEOUT = "2m";

  public OpenSearchConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }

  public static final ConfigEntry<String> ENTITY_OPEN_SEARCH_USERNAME =
      new ConfigBuilder(OPEN_SEARCH_USERNAME_KEY)
          .doc("The username of OpenSearch")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG);

  public static final ConfigEntry<String> ENTITY_OPEN_SEARCH_PASSWORD =
      new ConfigBuilder(OPEN_SEARCH_PASSWORD_KEY)
          .doc("The password of OpenSearch")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG);

  public static final ConfigEntry<String> ENTITY_OPEN_SEARCH_URL =
      new ConfigBuilder(OPEN_SEARCH_URL_KEY)
          .doc("The URL of OpenSearch")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG);

  public static final ConfigEntry<Integer> ENTITY_OPEN_SEARCH_WRITE_MAX_RETRY =
      new ConfigBuilder(OPEN_SEARCH_WRITE_MAX_RETRY)
          .doc("The maximum number of retries to write operations to OpenSearch")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_WRITE_MAX_RETRY);

  public static final ConfigEntry<Integer> ENTITY_OPEN_SEARCH_RETRY_BACKOFF_MS =
      new ConfigBuilder(OPEN_SEARCH_RETRY_BACKOFF_MS)
          .doc("The backoff time in milliseconds between retries to send data to OpenSearch")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_RETRY_BACKOFF_MS);

  public static final ConfigEntry<Integer> ENTITY_OPEN_SEARCH_QUERY_TIMEOUT_MS =
      new ConfigBuilder(OPEN_SEARCH_QUERY_TIMEOUT_MS)
          .doc("The timeout in milliseconds for OpenSearch queries")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_QUERY_TIMEOUT_MS);

  public static final ConfigEntry<Integer> ENTITY_OPEN_SEARCH_MAX_QUERY_THREAD =
      new ConfigBuilder(OPEN_SEARCH_MAX_QUERY_THREAD)
          .doc("The maximum number of threads for OpenSearch queries")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_MAX_QUERY_THREAD);

  public static final ConfigEntry<Integer> ENTITY_OPEN_SEARCH_MAX_QUERY_QUEUE_SIZE =
      new ConfigBuilder(OPEN_SEARCH_MAX_QUERY_QUEUE_SIZE)
          .doc("The maximum queue size for OpenSearch queries")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(DEFAULT_MAX_QUERY_QUEUE_SIZE);
  public static final ConfigEntry<String> ENTITY_OPEN_SEARCH_BACKGROUND_QUERY_TIMEOUT =
      new ConfigBuilder(OPEN_SEARCH_BACKGROUND_QUERY_TIMEOUT)
          .doc("The timeout for background queries in OpenSearch, like 30s, 5m etc.")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .createWithDefault(DEFAULT_BACKGROUND_QUERY_QUEUE_TIMEOUT);

  public String getOpenSearchUrl() {
    return get(ENTITY_OPEN_SEARCH_URL);
  }

  public String getOpenSearchUsername() {
    return get(ENTITY_OPEN_SEARCH_USERNAME);
  }

  public String getOpenSearchPassword() {
    return get(ENTITY_OPEN_SEARCH_PASSWORD);
  }

  public int getOpenSearchWriteMaxRetry() {
    return get(ENTITY_OPEN_SEARCH_WRITE_MAX_RETRY);
  }

  public int getOpenSearchRetryBackoffMs() {
    return get(ENTITY_OPEN_SEARCH_RETRY_BACKOFF_MS);
  }

  public int getOpenSearchQueryTimeoutMs() {
    return get(ENTITY_OPEN_SEARCH_QUERY_TIMEOUT_MS);
  }

  public int getOpenSearchMaxQueryThread() {
    return get(ENTITY_OPEN_SEARCH_MAX_QUERY_THREAD);
  }

  public int getOpenSearchMaxQueryQueueSize() {
    return get(ENTITY_OPEN_SEARCH_MAX_QUERY_QUEUE_SIZE);
  }

  public String getOpenSearchBackgroundQueryTimeout() {
    return get(ENTITY_OPEN_SEARCH_BACKGROUND_QUERY_TIMEOUT);
  }
}
