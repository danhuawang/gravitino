/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static java.util.Collections.emptyMap;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOpenSearchConfig {

  @Test
  public void testDefaultSearchConfig() {
    Map<String, String> properties = emptyMap();
    // Create an instance of OpenSearchConfig
    OpenSearchConfig config = new OpenSearchConfig(properties);
    Assertions.assertThrows(NoSuchElementException.class, config::getOpenSearchPassword);
    Assertions.assertThrows(NoSuchElementException.class, config::getOpenSearchUsername);
    Assertions.assertThrows(NoSuchElementException.class, config::getOpenSearchUrl);
    Assertions.assertEquals(
        OpenSearchConfig.DEFAULT_WRITE_MAX_RETRY, config.getOpenSearchWriteMaxRetry());
    Assertions.assertEquals(
        OpenSearchConfig.DEFAULT_RETRY_BACKOFF_MS, config.getOpenSearchRetryBackoffMs());
  }

  @Test
  public void testSearchConfig() {
    Map<String, String> properties =
        ImmutableMap.of(
            OpenSearchConfig.OPEN_SEARCH_URL_KEY,
            "https://localhost:9200",
            OpenSearchConfig.OPEN_SEARCH_USERNAME_KEY,
            "admin",
            OpenSearchConfig.OPEN_SEARCH_PASSWORD_KEY,
            "admin",
            OpenSearchConfig.OPEN_SEARCH_WRITE_MAX_RETRY,
            "9",
            OpenSearchConfig.OPEN_SEARCH_RETRY_BACKOFF_MS,
            "99");
    OpenSearchConfig config = new OpenSearchConfig(properties);
    Assertions.assertEquals("https://localhost:9200", config.getOpenSearchUrl());
    Assertions.assertEquals("admin", config.getOpenSearchUsername());
    Assertions.assertEquals("admin", config.getOpenSearchPassword());
    Assertions.assertEquals(9, config.getOpenSearchWriteMaxRetry());
    Assertions.assertEquals(99, config.getOpenSearchRetryBackoffMs());
  }
}
