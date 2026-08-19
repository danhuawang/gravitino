/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.encryption.kms.KmsAuthenticationException;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientFactory;
import org.apache.gravitino.encryption.kms.KmsConfigurationException;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestVaultTransitKmsClientFactory {

  private static final String PROVIDER = "primary";
  private static final String TOKEN_ENVIRONMENT_VARIABLE = "GRAVITINO_TEST_VAULT_TOKEN";

  private final AtomicReference<String> requestedPath = new AtomicReference<>();
  private final AtomicReference<String> requestedToken = new AtomicReference<>();
  private final AtomicInteger environmentLookups = new AtomicInteger();
  private final Map<String, String> environment = new HashMap<>();

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    environment.clear();
    environment.put(TOKEN_ENVIRONMENT_VARIABLE, "read-only-token");
    environmentLookups.set(0);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private KmsClientFactory factory() {
    return new VaultTransitKmsClientFactory(
        name -> {
          environmentLookups.incrementAndGet();
          return environment.get(name);
        });
  }

  @Test
  void createsWorkingClientWithDefaultMount() {
    try (KmsClient client = factory().create(PROVIDER, properties())) {
      client.getKeyProperties(reference("customer-key"));
      assertEquals("/v1/transit/keys/customer-key", requestedPath.get());
    }
  }

  @Test
  void createsWorkingClientWithCustomMount() {
    Map<String, String> properties = properties();
    properties.put(VaultTransitKmsClientFactory.TRANSIT_MOUNT, "team/transit");

    try (KmsClient client = factory().create(PROVIDER, properties)) {
      client.getKeyProperties(reference("customer-key"));
      assertEquals("/v1/team/transit/keys/customer-key", requestedPath.get());
    }
  }

  @Test
  void resolvesEnvironmentCredentialOncePerClient() {
    try (KmsClient client = factory().create(PROVIDER, properties())) {
      assertEquals(1, environmentLookups.get());
      client.getKeyProperties(reference("customer-key"));
      assertEquals("read-only-token", requestedToken.get());

      environment.put(TOKEN_ENVIRONMENT_VARIABLE, "replacement-token");
      client.getKeyProperties(reference("customer-key"));
      assertEquals("read-only-token", requestedToken.get());
      assertEquals(1, environmentLookups.get());
    }

    try (KmsClient client = factory().create(PROVIDER, properties())) {
      client.getKeyProperties(reference("customer-key"));
      assertEquals("replacement-token", requestedToken.get());
      assertEquals(2, environmentLookups.get());
    }
  }

  @Test
  void serviceLoaderDiscoversVaultTransitFactory() {
    Set<Class<?>> factoryClasses = new HashSet<>();
    for (KmsClientFactory factory : ServiceLoader.load(KmsClientFactory.class)) {
      factoryClasses.add(factory.getClass());
    }

    assertTrue(factoryClasses.contains(VaultTransitKmsClientFactory.class));
  }

  @Test
  void rejectsMissingRequiredConfiguration() {
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, null));

    Map<String, String> missingAddress = properties();
    missingAddress.remove(VaultTransitKmsClientFactory.SERVICE_ADDRESS);
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, missingAddress));

    Map<String, String> missingEnvironmentVariable = properties();
    missingEnvironmentVariable.remove(VaultTransitKmsClientFactory.CREDENTIAL_ENVIRONMENT_VARIABLE);
    assertThrows(
        KmsConfigurationException.class,
        () -> factory().create(PROVIDER, missingEnvironmentVariable));

    Map<String, String> missingCredentialMethod = properties();
    missingCredentialMethod.remove(VaultTransitKmsClientFactory.CREDENTIAL_METHOD);
    assertThrows(
        KmsConfigurationException.class, () -> factory().create(PROVIDER, missingCredentialMethod));
  }

  @Test
  void rejectsInvalidProviderAndUnknownConfiguration() {
    assertThrows(KmsConfigurationException.class, () -> factory().create(" ", properties()));
    assertThrows(
        KmsConfigurationException.class, () -> factory().create(" primary ", properties()));

    for (String property : new String[] {"credential.token", "credential.tokenFile"}) {
      Map<String, String> properties = properties();
      properties.put(property, "must-not-be-accepted");
      assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));
    }
  }

  @Test
  void rejectsUnsupportedCredentialMethod() {
    Map<String, String> properties = properties();
    properties.put(VaultTransitKmsClientFactory.CREDENTIAL_METHOD, "token_file");

    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));
  }

  @Test
  void rejectsInvalidServiceAddress() {
    assertInvalidServiceAddress("file:///tmp/vault");
    assertInvalidServiceAddress("http://user@localhost");
    assertInvalidServiceAddress("http://localhost/vault");
    assertInvalidServiceAddress("http://localhost?namespace=team");
    assertInvalidServiceAddress("http://localhost#fragment");
  }

  @Test
  void requiresExplicitOptInForPlaintextHttp() {
    Map<String, String> properties = properties();
    properties.remove(VaultTransitKmsClientFactory.ALLOW_INSECURE_HTTP);
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));

    properties.put(VaultTransitKmsClientFactory.ALLOW_INSECURE_HTTP, "false");
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));

    properties.put(VaultTransitKmsClientFactory.ALLOW_INSECURE_HTTP, "not-a-boolean");
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));

    properties.put(VaultTransitKmsClientFactory.SERVICE_ADDRESS, "https://vault.example:8200");
    properties.remove(VaultTransitKmsClientFactory.ALLOW_INSECURE_HTTP);
    try (KmsClient ignored = factory().create(PROVIDER, properties)) {
      assertEquals(1, environmentLookups.get());
    }
  }

  @Test
  void rejectsInvalidMountAndEnvironmentVariableName() {
    for (String mount : new String[] {"", "/transit", "transit/", "team//transit", ".", ".."}) {
      Map<String, String> properties = properties();
      properties.put(VaultTransitKmsClientFactory.TRANSIT_MOUNT, mount);
      assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));
    }

    Map<String, String> properties = properties();
    properties.put(VaultTransitKmsClientFactory.CREDENTIAL_ENVIRONMENT_VARIABLE, "INVALID-NAME");
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));
  }

  @Test
  void rejectsMissingOrMalformedEnvironmentCredentialAtCreationWithoutDisclosure() {
    for (String token : new String[] {null, "", " ", "secret-token\ninvalid"}) {
      if (token == null) {
        environment.remove(TOKEN_ENVIRONMENT_VARIABLE);
      } else {
        environment.put(TOKEN_ENVIRONMENT_VARIABLE, token);
      }
      KmsAuthenticationException exception =
          assertThrows(
              KmsAuthenticationException.class, () -> factory().create(PROVIDER, properties()));
      if (token != null && token.contains("secret-token")) {
        assertFalse(exception.toString().contains(token));
        assertFalse(exception.getCause().toString().contains(token));
      }
    }
    assertNull(requestedPath.get());
  }

  private KmsReference reference(String keyId) {
    return new KmsReference(PROVIDER, keyId);
  }

  private Map<String, String> properties() {
    Map<String, String> properties = new HashMap<>();
    properties.put(
        VaultTransitKmsClientFactory.SERVICE_ADDRESS,
        String.format("http://127.0.0.1:%s", server.getAddress().getPort()));
    properties.put(VaultTransitKmsClientFactory.ALLOW_INSECURE_HTTP, "true");
    properties.put(
        VaultTransitKmsClientFactory.CREDENTIAL_ENVIRONMENT_VARIABLE, TOKEN_ENVIRONMENT_VARIABLE);
    properties.put(VaultTransitKmsClientFactory.CREDENTIAL_METHOD, "environment_variable");
    return properties;
  }

  private void assertInvalidServiceAddress(String address) {
    Map<String, String> properties = properties();
    properties.put(VaultTransitKmsClientFactory.SERVICE_ADDRESS, address);
    assertThrows(KmsConfigurationException.class, () -> factory().create(PROVIDER, properties));
  }

  private void respond(HttpExchange exchange) throws IOException {
    requestedPath.set(exchange.getRequestURI().getRawPath());
    requestedToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
    byte[] response =
        "{\"data\":{\"supports_encryption\":true,\"supports_decryption\":true}}"
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
