/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.encryption.kms.TestKmsClientContract;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestVaultTransitClient extends TestKmsClientContract {

  private static final String SOURCE = "primary";
  private static final String USABLE_KEY = "customer-key";
  private static final String MISSING_KEY = "missing-key";

  private final AtomicReference<String> requestedPath = new AtomicReference<>();
  private final AtomicReference<String> requestedToken = new AtomicReference<>();

  private HttpServer server;
  private VaultTransitClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();

    client =
        new VaultTransitClient(
            SOURCE,
            URI.create(String.format("http://127.0.0.1:%s", server.getAddress().getPort())),
            "transit",
            "read-only-token",
            true);
  }

  @AfterEach
  void stopServer() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  @Override
  protected KmsClient client() {
    return client;
  }

  @Override
  protected KmsReference usableKey() {
    return reference(USABLE_KEY);
  }

  @Override
  protected KmsReference missingKey() {
    return reference(MISSING_KEY);
  }

  @Test
  void readsVaultTransitKeyMetadata() {
    client.getKeyProperties(usableKey());

    assertEquals("/v1/transit/keys/customer-key", requestedPath.get());
    assertEquals("read-only-token", requestedToken.get());
  }

  @Test
  void closesOwnedConnection() {
    client.close();

    assertThrows(ConnectionFailedException.class, () -> client.getKeyProperties(usableKey()));
  }

  private KmsReference reference(String keyId) {
    return new KmsReference(SOURCE, keyId);
  }

  private void respond(HttpExchange exchange) throws IOException {
    requestedPath.set(exchange.getRequestURI().getRawPath());
    requestedToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));

    boolean missing = exchange.getRequestURI().getRawPath().endsWith("/" + MISSING_KEY);
    byte[] response =
        (missing
                ? "{\"errors\":[]}"
                : "{\"data\":{\"type\":\"aes256-gcm96\",\"supports_encryption\":true,"
                    + "\"supports_decryption\":true}}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(missing ? 404 : 200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
