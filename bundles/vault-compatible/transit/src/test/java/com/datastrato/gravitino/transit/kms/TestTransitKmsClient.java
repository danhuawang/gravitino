/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.kms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.transit.common.TransitConnection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.encryption.kms.KmsAuthenticationException;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the typed, non-owning KMS view over a shared Transit connection. */
public class TestTransitKmsClient {

  private static final String PROVIDER_NAME = "Transit test provider";
  private static final String PROVIDER = "primary";
  private static final String KEY_ID = "customer key";
  private static final String VALID_RESPONSE =
      "{\"data\":{\"supports_encryption\":true,\"supports_decryption\":true}}";

  private final AtomicInteger responseStatus = new AtomicInteger(HttpStatus.SC_SUCCESS);
  private final AtomicInteger requestCount = new AtomicInteger();
  private final AtomicReference<String> responseBody = new AtomicReference<>(VALID_RESPONSE);
  private final AtomicReference<String> requestedPath = new AtomicReference<>();

  private HttpServer server;
  private TransitConnection connection;
  private TransitKmsClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
    connection =
        new TransitConnection(
            PROVIDER_NAME,
            URI.create(String.format("http://127.0.0.1:%s", server.getAddress().getPort())),
            "read-only-token",
            true);
    client = createClient();
  }

  @AfterEach
  void stopServer() {
    if (connection != null) {
      connection.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void mapsKeyPropertiesAndEncodesNestedMountAndKeySegment() {
    KmsReference reference = reference(KEY_ID);

    KmsKeyProperties properties = client.getKeyProperties(reference).orElseThrow();

    assertSame(reference, properties.reference());
    assertTrue(properties.enabled());
    assertTrue(properties.supportsWrapping());
    assertTrue(properties.supportsUnwrapping());
    assertEquals("/v1/team/transit/keys/customer%20key", requestedPath.get());
  }

  @Test
  void mapsAuthoritativeMissingKeyResponseToEmpty() {
    responseStatus.set(HttpStatus.SC_NOT_FOUND);
    responseBody.set("{\"errors\":[]}");

    assertTrue(client.getKeyProperties(reference("missing-key")).isEmpty());
  }

  @Test
  void rejectsMissingTransitRouteAsConnectionFailure() {
    responseStatus.set(HttpStatus.SC_NOT_FOUND);
    responseBody.set("{\"errors\":[\"unsupported path\"]}");

    assertThrows(
        ConnectionFailedException.class, () -> client.getKeyProperties(reference("missing-key")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-json", "{}"})
  void rejectsNonAuthoritativeNotFoundResponses(String body) {
    responseStatus.set(HttpStatus.SC_NOT_FOUND);
    responseBody.set(body);

    assertThrows(
        ConnectionFailedException.class, () -> client.getKeyProperties(reference("missing-key")));
  }

  @ParameterizedTest
  @ValueSource(ints = {HttpStatus.SC_SUCCESS, 299})
  void acceptsSuccessfulStatusRange(int statusCode) {
    responseStatus.set(statusCode);

    assertTrue(client.getKeyProperties(reference(KEY_ID)).isPresent());
  }

  @ParameterizedTest
  @ValueSource(ints = {HttpStatus.SC_REDIRECTION, 400, 405, 429, 500, 502, 503})
  void mapsProviderErrorsToConnectionFailures(int statusCode) {
    responseStatus.set(statusCode);

    assertThrows(ConnectionFailedException.class, () -> client.getKeyProperties(reference(KEY_ID)));
    assertEquals(1, requestCount.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN})
  void mapsRejectedCredentialsToAuthenticationFailures(int statusCode) {
    responseStatus.set(statusCode);

    assertThrows(
        KmsAuthenticationException.class, () -> client.getKeyProperties(reference(KEY_ID)));
    assertEquals(1, requestCount.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-json",
        "{}",
        "{\"data\":{}}",
        "{\"data\":{\"supports_encryption\":\"true\",\"supports_decryption\":true}}",
        "{\"data\":{\"supports_encryption\":true,\"supports_decryption\":\"true\"}}"
      })
  void mapsMalformedResponsesToConnectionFailures(String body) {
    responseBody.set(body);

    assertThrows(ConnectionFailedException.class, () -> client.getKeyProperties(reference(KEY_ID)));
  }

  @Test
  void mapsSoftDeletedKeyToMissing() {
    responseBody.set(
        "{\"data\":{\"supports_encryption\":true,\"supports_decryption\":true,"
            + "\"soft_deleted\":true}}");

    assertTrue(client.getKeyProperties(reference(KEY_ID)).isEmpty());
  }

  @Test
  void validatesReferencesBeforeSendingARequest() {
    assertThrows(IllegalArgumentException.class, () -> client.getKeyProperties(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.getKeyProperties(new KmsReference("secondary", KEY_ID)));
    for (String keyId : new String[] {".", "..", "nested/key", "nested\\key"}) {
      assertThrows(IllegalArgumentException.class, () -> client.getKeyProperties(reference(keyId)));
    }
    assertEquals(0, requestCount.get());
  }

  @Test
  void closingNonOwningViewDoesNotCloseSharedConnection() {
    TransitKmsClient secondView = createClient();

    client.close();
    assertTrue(secondView.getKeyProperties(reference(KEY_ID)).isPresent());

    connection.close();
    assertThrows(
        ConnectionFailedException.class, () -> secondView.getKeyProperties(reference(KEY_ID)));
  }

  private TransitKmsClient createClient() {
    return connection.kms(PROVIDER, "team/transit");
  }

  private KmsReference reference(String keyId) {
    return new KmsReference(PROVIDER, keyId);
  }

  private void respond(HttpExchange exchange) throws IOException {
    requestCount.incrementAndGet();
    requestedPath.set(exchange.getRequestURI().getRawPath());
    byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(responseStatus.get(), response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
