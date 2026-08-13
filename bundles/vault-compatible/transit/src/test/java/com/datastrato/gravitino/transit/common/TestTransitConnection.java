/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests shared Transit connection behavior and lifecycle. */
public class TestTransitConnection {

  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  private final AtomicInteger responseStatus = new AtomicInteger(HttpStatus.SC_SUCCESS);
  private final AtomicInteger requestCount = new AtomicInteger();
  private final AtomicReference<byte[]> responseBody =
      new AtomicReference<>("{}".getBytes(StandardCharsets.UTF_8));
  private final AtomicReference<String> responseLocation = new AtomicReference<>();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

  private volatile boolean chunkedResponse;

  private HttpServer server;
  private ExecutorService serverExecutor;
  private TransitConnection connection;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newFixedThreadPool(16);
    server.setExecutor(serverExecutor);
    server.createContext("/", this::respond);
    server.start();
    connection =
        new TransitConnection(
            "Transit test provider",
            URI.create(String.format("http://127.0.0.1:%s", server.getAddress().getPort())),
            "read-only-token",
            true);
  }

  @AfterEach
  void stopServer() {
    if (connection != null) {
      connection.close();
    }
    if (server != null) {
      server.stop(0);
    }
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
    }
  }

  @Test
  void encodesSameOriginPathSegmentsAndSetsAuthenticationHeaders() {
    connection.get(List.of("team transit", "keys", "key+%?#雪"));

    assertEquals(1, requests.size());
    RecordedRequest request = requests.get(0);
    assertEquals("/v1/team%20transit/keys/key%2B%25%3F%23%E9%9B%AA", request.rawPath);
    assertEquals("read-only-token", request.token);
    assertEquals("application/json", request.accept);
  }

  @ParameterizedTest
  @ValueSource(ints = {HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN})
  void doesNotRetryRejectedCredentials(int statusCode) {
    responseStatus.set(statusCode);

    TransitHttpResponse response = connection.get(List.of("transit", "keys", "customer-key"));

    assertEquals(statusCode, response.statusCode());
    assertEquals(1, requestCount.get());
  }

  @Test
  void neverRetriesNonAuthenticationFailure() {
    responseStatus.set(HttpStatus.SC_SERVICE_UNAVAILABLE);

    TransitHttpResponse response = connection.get(List.of("transit", "keys", "customer-key"));

    assertEquals(HttpStatus.SC_SERVICE_UNAVAILABLE, response.statusCode());
    assertEquals(1, requestCount.get());
  }

  @Test
  void neverFollowsRedirectsOrForwardsCredentials() throws IOException {
    AtomicInteger redirectTargetRequestCount = new AtomicInteger();
    AtomicReference<String> redirectedToken = new AtomicReference<>();
    HttpServer redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    redirectTarget.createContext(
        "/",
        exchange -> {
          redirectTargetRequestCount.incrementAndGet();
          redirectedToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
          exchange.sendResponseHeaders(HttpStatus.SC_SUCCESS, -1);
          exchange.close();
        });
    redirectTarget.start();
    try {
      responseStatus.set(HttpStatus.SC_MOVED_TEMPORARILY);
      responseLocation.set(
          String.format("http://127.0.0.1:%s/capture", redirectTarget.getAddress().getPort()));

      TransitHttpResponse response = connection.get(List.of("transit", "keys", "customer-key"));

      assertEquals(HttpStatus.SC_MOVED_TEMPORARILY, response.statusCode());
      assertEquals(1, requestCount.get());
      assertEquals(0, redirectTargetRequestCount.get());
      assertNull(redirectedToken.get());
    } finally {
      redirectTarget.stop(0);
    }
  }

  @Test
  void enforcesKnownResponseSizeLimit() {
    responseBody.set(new byte[MAX_RESPONSE_BYTES + 1]);

    assertThrows(
        ConnectionFailedException.class,
        () -> connection.get(List.of("transit", "keys", "customer-key")));
  }

  @Test
  void enforcesChunkedResponseSizeLimit() {
    responseBody.set(new byte[MAX_RESPONSE_BYTES + 1]);
    chunkedResponse = true;

    assertThrows(
        ConnectionFailedException.class,
        () -> connection.get(List.of("transit", "keys", "customer-key")));
  }

  @Test
  void acceptsResponseAtSizeLimit() {
    responseBody.set(new byte[MAX_RESPONSE_BYTES]);

    assertEquals(
        MAX_RESPONSE_BYTES,
        connection.get(List.of("transit", "keys", "customer-key")).body().length);
  }

  @Test
  void rejectsInvalidPathsBeforeSendingARequest() {
    assertThrows(IllegalArgumentException.class, () -> connection.get(null));
    assertThrows(IllegalArgumentException.class, () -> connection.get(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> connection.get(Arrays.asList("transit", null)));
    assertThrows(IllegalArgumentException.class, () -> connection.get(List.of("transit", "")));
    assertThrows(IllegalArgumentException.class, () -> connection.get(List.of("transit", ".")));
    assertThrows(IllegalArgumentException.class, () -> connection.get(List.of("transit", "..")));
    assertEquals(0, requestCount.get());
  }

  @Test
  void rejectsNonOriginServiceAddressesAtConstruction() {
    for (String address :
        new String[] {
          "ftp://vault.example",
          "https://user@vault.example",
          "https://vault.example/v1",
          "https://vault.example?query=true",
          "https://vault.example#fragment"
        }) {
      assertThrows(
          TransitConfigurationException.class,
          () ->
              new TransitConnection("Transit test provider", URI.create(address), "unused-token"));
    }
    assertEquals(0, requestCount.get());
  }

  @Test
  void rejectsInvalidBearerTokensAtConstructionWithoutDisclosure() {
    URI serviceAddress =
        URI.create(String.format("http://127.0.0.1:%s", server.getAddress().getPort()));

    for (String token : new String[] {null, "", " ", "token\nvalue"}) {
      assertThrows(
          TransitAuthenticationException.class,
          () -> new TransitConnection("Transit test provider", serviceAddress, token, true));
    }
    String malformedSecret = "do-not-disclose-this-secret\ninvalid";
    TransitAuthenticationException exception =
        assertThrows(
            TransitAuthenticationException.class,
            () ->
                new TransitConnection(
                    "Transit test provider", serviceAddress, malformedSecret, true));
    assertFalse(exception.toString().contains(malformedSecret));
    assertEquals(0, requestCount.get());
  }

  @Test
  void rejectsPlaintextHttpWithoutExplicitOptIn() {
    URI serviceAddress =
        URI.create(String.format("http://127.0.0.1:%s", server.getAddress().getPort()));

    assertThrows(
        TransitConfigurationException.class,
        () -> new TransitConnection("Transit test provider", serviceAddress, "unused-token"));
    assertEquals(0, requestCount.get());
  }

  @Test
  void supportsConcurrentReadsOnOneConnection() throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(16);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        int key = index;
        results.add(
            callers.submit(
                () ->
                    connection
                        .get(List.of("transit", "keys", "customer-key-" + key))
                        .statusCode()));
      }
      for (Future<Integer> result : results) {
        assertEquals(HttpStatus.SC_SUCCESS, result.get());
      }
      assertEquals(32, requestCount.get());
    } finally {
      callers.shutdownNow();
    }
  }

  @Test
  void closesIdempotentlyAndRejectsLaterRequests() {
    connection.close();
    connection.close();

    assertThrows(
        ConnectionFailedException.class,
        () -> connection.get(List.of("transit", "keys", "customer-key")));
    assertEquals(0, requestCount.get());
  }

  private void respond(HttpExchange exchange) throws IOException {
    requestCount.incrementAndGet();
    requests.add(
        new RecordedRequest(
            exchange.getRequestURI().getRawPath(),
            exchange.getRequestHeaders().getFirst("X-Vault-Token"),
            exchange.getRequestHeaders().getFirst("Accept")));
    byte[] body = responseBody.get();
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (responseLocation.get() != null) {
      exchange.getResponseHeaders().set("Location", responseLocation.get());
    }
    exchange.sendResponseHeaders(responseStatus.get(), chunkedResponse ? 0 : body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static final class RecordedRequest {
    private final String rawPath;
    private final String token;
    private final String accept;

    private RecordedRequest(String rawPath, String token, String accept) {
      this.rawPath = rawPath;
      this.token = token;
      this.accept = accept;
    }
  }
}
