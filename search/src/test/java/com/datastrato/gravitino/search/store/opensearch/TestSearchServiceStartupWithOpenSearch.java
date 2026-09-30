/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.search.config.SearchConfig.ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL;
import static com.datastrato.gravitino.search.config.SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.gravitino.Config;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestSearchServiceStartupWithOpenSearch {

  private static final String UNAVAILABLE =
      "{\"error\":{\"type\":\"unavailable\",\"reason\":\"unavailable\"},\"status\":503}";
  private static final String UPDATE_BY_QUERY =
      "{\"took\":1,\"timed_out\":false,\"total\":0,\"updated\":0,\"deleted\":0,\"batches\":0,"
          + "\"version_conflicts\":0,\"noops\":0,\"retries\":{\"bulk\":0,\"search\":0},"
          + "\"throttled_millis\":0,\"requests_per_second\":-1.0,\"throttled_until_millis\":0,"
          + "\"failures\":[]}";

  private final AtomicBoolean openSearchAvailable = new AtomicBoolean(false);
  private final AtomicInteger requests = new AtomicInteger();
  private volatile long responseDelayMs;
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void testHealthyOpenSearchIsAvailableAfterFirstAttempt() {
    openSearchAvailable.set(true);
    SearchService service = new SearchService(config(true));
    try {
      service.awaitFirstInitializationAttempt();
      assertTrue(service.isAvailable());
    } finally {
      service.close();
    }
  }

  @Test
  void testUnavailableOpenSearchDoesNotFailStartupAndRecoversInBackground() {
    SearchService service = new SearchService(config(true));
    try {
      // The listener relies on this returning promptly once the first attempt has failed.
      assertTimeoutPreemptively(Duration.ofSeconds(10), service::awaitFirstInitializationAttempt);
      assertFalse(service.isAvailable());
      assertTrue(service.skipEventWhileUnavailable());

      openSearchAvailable.set(true);
      Awaitility.await().atMost(Duration.ofSeconds(10)).until(service::isAvailable);

      assertFalse(service.skipEventWhileUnavailable());

      // Availability tracks startup initialization only.
      openSearchAvailable.set(false);
      assertTrue(service.isAvailable());
    } finally {
      service.close();
    }
  }

  @Test
  void testConstructorDoesNotWaitForSlowOpenSearch() {
    responseDelayMs = 5000;
    long start = System.nanoTime();
    SearchService service = new SearchService(config(false));
    try {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsedMs < 2000, "constructor blocked for " + elapsedMs + " ms");
      assertFalse(service.isAvailable());
    } finally {
      service.close();
    }
  }

  @Test
  void testCloseStopsBackgroundInitialization() {
    SearchService service = new SearchService(config(true));
    service.awaitFirstInitializationAttempt();
    assertFalse(service.isAvailable());
    service.close();
    // Let a request already in flight at close() reach the stub before taking the baseline.
    Awaitility.await().pollDelay(Duration.ofMillis(500)).until(() -> true);
    int requestsAfterClose = requests.get();

    openSearchAvailable.set(true);
    Awaitility.await()
        .during(Duration.ofMillis(2500))
        .atMost(Duration.ofSeconds(3))
        .until(() -> requests.get() == requestsAfterClose);
    assertFalse(service.isAvailable());
  }

  /**
   * Builds the search config for the stub server. With {@code fastRetry}, each attempt checks the
   * templates once; otherwise the default retry count and backoff apply.
   */
  private Config config(boolean fastRetry) {
    ImmutableMap.Builder<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put(
                ENTITY_GRAVITINO_SEARCH_STORAGE_IMPL.getKey(),
                GRAVITINO_SEARCH_STORAGE_IMPL_OPENSEARCH)
            .put(
                OpenSearchConfig.OPEN_SEARCH_URL_KEY,
                "http://127.0.0.1:" + server.getAddress().getPort())
            .put(OpenSearchConfig.OPEN_SEARCH_USERNAME_KEY, "admin")
            .put(OpenSearchConfig.OPEN_SEARCH_PASSWORD_KEY, "admin");
    if (fastRetry) {
      properties
          .put(OpenSearchConfig.OPEN_SEARCH_WRITE_MAX_RETRY, "1")
          .put(OpenSearchConfig.OPEN_SEARCH_RETRY_BACKOFF_MS, "0");
    }
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.getAllConfig()).thenReturn(properties.build());
    return config;
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    if (responseDelayMs > 0) {
      try {
        Thread.sleep(responseDelayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    String path = exchange.getRequestURI().getPath();
    if (!openSearchAvailable.get()) {
      respond(exchange, 503, UNAVAILABLE);
    } else if (path.equals("/_index_template")) {
      respond(exchange, 200, indexTemplates());
    } else if (path.endsWith("/_update_by_query")) {
      respond(exchange, 200, UPDATE_BY_QUERY);
    } else {
      respond(exchange, 404, "{}");
    }
  }

  private static String indexTemplates() {
    return OpenSearchStorage.ENTITY_TYPE_TO_INDEX_SUFFIX.values().stream()
        .map(
            suffix ->
                String.format(
                    "{\"name\":\"%s_template_v1\",\"index_template\":"
                        + "{\"index_patterns\":[\"%s*\"],\"composed_of\":[]}}",
                    suffix, suffix))
        .collect(Collectors.joining(",", "{\"index_templates\":[", "]}"));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
