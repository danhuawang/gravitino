/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.integration.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.encryption.kms.KmsAuthenticationException;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientFactory;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Shared integration-test contract for Transit-compatible KMS providers. */
public abstract class AbstractTransitKmsClientIT {

  private static final int API_PORT = 8200;
  private static final String ROOT_TOKEN = "gravitino-kms-test-root-token";
  private static final String CLIENT_TOKEN_ENVIRONMENT_VARIABLE = "GRAVITINO_TRANSIT_IT_TOKEN";
  private static final String INVALID_TOKEN_ENVIRONMENT_VARIABLE =
      "GRAVITINO_TRANSIT_IT_INVALID_TOKEN";
  private static final String SOURCE = "test";
  private static final String USABLE_KEY = "usable-key";
  private static final String SIGNING_KEY = "signing-key";

  private GenericContainer<?> container;

  @BeforeAll
  void startBackend() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse(image()))
            .withCommand(
                "server",
                "-dev",
                String.format("-dev-root-token-id=%s", ROOT_TOKEN),
                "-dev-listen-address=0.0.0.0:8200")
            .withEnv(addressEnvironmentVariable(), "http://127.0.0.1:8200")
            .withEnv(tokenEnvironmentVariable(), ROOT_TOKEN)
            .withExposedPorts(API_PORT)
            .waitingFor(
                Wait.forHttp("/v1/sys/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));
    container.start();

    runCommand("secrets", "enable", "transit");
    runCommand("write", "-f", String.format("transit/keys/%s", USABLE_KEY));
    runCommand("write", "-f", String.format("transit/keys/%s", SIGNING_KEY), "type=ecdsa-p256");
  }

  @AfterAll
  void stopBackend() {
    if (container != null) {
      container.stop();
    }
  }

  @Test
  void inspectsRealTransitKeyMetadata() {
    try (KmsClient client = factory().create(SOURCE, properties())) {
      KmsReference usableReference = new KmsReference(api(), SOURCE, USABLE_KEY);
      KmsKeyProperties usable = client.getKeyProperties(usableReference).orElseThrow();

      assertEquals(usableReference, usable.reference());
      assertTrue(usable.enabled());
      assertTrue(usable.supportsWrapping());
      assertTrue(usable.supportsUnwrapping());

      KmsKeyProperties signing =
          client.getKeyProperties(new KmsReference(api(), SOURCE, SIGNING_KEY)).orElseThrow();
      assertTrue(signing.enabled());
      assertFalse(signing.supportsWrapping());
      assertFalse(signing.supportsUnwrapping());

      assertFalse(
          client.getKeyProperties(new KmsReference(api(), SOURCE, "missing-key")).isPresent());
    }
  }

  @Test
  void rejectsInvalidEnvironmentCredential() {
    try (KmsClient client =
        factory().create(SOURCE, properties(INVALID_TOKEN_ENVIRONMENT_VARIABLE))) {
      assertThrows(
          KmsAuthenticationException.class,
          () -> client.getKeyProperties(new KmsReference(api(), SOURCE, USABLE_KEY)));
    }
  }

  /**
   * Returns the container image that provides the Transit-compatible backend.
   *
   * @return the container image
   */
  protected abstract String image();

  /**
   * Returns the provider CLI executable available in the container.
   *
   * @return the executable name
   */
  protected abstract String executable();

  /**
   * Returns the provider CLI environment variable containing the service address.
   *
   * @return the address environment-variable name
   */
  protected abstract String addressEnvironmentVariable();

  /**
   * Returns the provider CLI environment variable containing the test token.
   *
   * @return the token environment-variable name
   */
  protected abstract String tokenEnvironmentVariable();

  /**
   * Returns the KMS API identifier exposed by the provider factory.
   *
   * @return the KMS API identifier
   */
  protected abstract String api();

  /**
   * Creates the provider factory under test.
   *
   * @return the provider factory
   */
  protected abstract KmsClientFactory factory();

  private Map<String, String> properties() {
    return properties(CLIENT_TOKEN_ENVIRONMENT_VARIABLE);
  }

  private Map<String, String> properties(String credentialEnvironmentVariable) {
    Map<String, String> properties = new HashMap<>();
    properties.put(
        "endpoint.address",
        String.format("http://%s:%s", container.getHost(), container.getMappedPort(API_PORT)));
    properties.put("endpoint.allowInsecureHttp", "true");
    properties.put("credential.method", "environment_variable");
    properties.put("credential.environmentVariable", credentialEnvironmentVariable);
    return properties;
  }

  private Container.ExecResult runCommand(String... arguments) throws Exception {
    String[] command = new String[arguments.length + 1];
    command[0] = executable();
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    Container.ExecResult result = container.execInContainer(command);
    assertEquals(
        0,
        result.getExitCode(),
        () ->
            String.format(
                "Transit fixture command failed: stdout=%s, stderr=%s",
                result.getStdout(), result.getStderr()));
    return result;
  }
}
