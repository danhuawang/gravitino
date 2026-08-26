/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.transit.openbao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.encryption.kms.TestKmsClientContract;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestOpenBaoTransitClient extends TestKmsClientContract {

  private static final String SOURCE = "primary";
  private static final String USABLE_KEY = "customer key";
  private static final String MISSING_KEY = "missing-key";
  private static final String SOFT_DELETED_KEY = "soft-deleted-key";
  private static final String SIGNING_KEY = "signing-key";

  private final AtomicReference<String> requestedPath = new AtomicReference<>();
  private final AtomicReference<String> requestedToken = new AtomicReference<>();

  private HttpServer server;
  private OpenBaoTransitClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();

    client =
        new OpenBaoTransitClient(
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
  void readsEncodedOpenBaoTransitKeyMetadata() {
    client.getKeyProperties(usableKey());

    assertEquals("/v1/transit/keys/customer%20key", requestedPath.get());
    assertEquals("read-only-token", requestedToken.get());
  }

  @Test
  void treatsSoftDeletedKeyAsMissing() {
    assertTrue(client.getKeyProperties(reference(SOFT_DELETED_KEY)).isEmpty());
  }

  @Test
  void supportsResponsesWithoutSoftDeleteExtension() {
    Optional<KmsKeyProperties> result = client.getKeyProperties(reference(SIGNING_KEY));

    assertTrue(result.isPresent());
    assertFalse(result.get().supportsWrapping());
    assertFalse(result.get().supportsUnwrapping());
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
    String path = exchange.getRequestURI().getRawPath();
    requestedPath.set(path);
    requestedToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));

    int statusCode = 200;
    String response;
    if (path.endsWith("/" + MISSING_KEY)) {
      statusCode = 404;
      response = "{\"errors\":[]}";
    } else if (path.endsWith("/" + SOFT_DELETED_KEY)) {
      response =
          "{\"data\":{\"soft_deleted\":true,\"supports_encryption\":true,"
              + "\"supports_decryption\":true}}";
    } else if (path.endsWith("/" + SIGNING_KEY)) {
      response = "{\"data\":{\"supports_encryption\":false,\"supports_decryption\":false}}";
    } else {
      response =
          "{\"data\":{\"soft_deleted\":false,\"supports_encryption\":true,"
              + "\"supports_decryption\":true}}";
    }

    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
