/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;

class TestOpenSearchContainer {
  private HttpsServer server;
  private WaitStrategyTarget target;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger initialFailures = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    // This self-signed keystore is a test fixture, never a production credential.
    char[] password = "changeit".toCharArray();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream input = getClass().getResourceAsStream("/opensearch-test.p12")) {
      keyStore.load(input, password);
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, password);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(keyManagers.getKeyManagers(), null, null);
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
    String credentials =
        OpenSearchContainer.DEFAULT_USERNAME + ":" + OpenSearchContainer.DEFAULT_PASSWORD;
    String authorization =
        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    server.createContext(
        "/_cluster/health",
        exchange -> {
          requests.incrementAndGet();
          int response =
              authorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                  ? (initialFailures.getAndDecrement() > 0 ? 503 : status.get())
                  : 401;
          exchange.sendResponseHeaders(response, -1);
          exchange.close();
        });
    server.start();
    target = mock(WaitStrategyTarget.class);
    InspectContainerResponse containerInfo = mock(InspectContainerResponse.class);
    when(containerInfo.getName()).thenReturn("test-opensearch");
    when(target.getContainerInfo()).thenReturn(containerInfo);
    when(target.getHost()).thenReturn("127.0.0.1");
    when(target.getExposedPorts()).thenReturn(Collections.singletonList(OpenSearchContainer.PORT));
    when(target.getMappedPort(OpenSearchContainer.PORT)).thenReturn(server.getAddress().getPort());
    when(target.getLivenessCheckPortNumbers())
        .thenReturn(Collections.singleton(server.getAddress().getPort()));
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void testBindPublishedPortOnAllInterfaces() {
    try (TestContainer container = new TestContainer()) {
      assertEquals("0.0.0.0", container.getEnvMap().get("network.host"));
    }
  }

  @Test
  void testWaitForPublishedHttpsEndpoint() {
    try (TestContainer container = new TestContainer()) {
      assertDoesNotThrow(() -> container.awaitReady(target));
      assertTrue(requests.get() > 0);
    }
  }

  @Test
  void testRejectUnhealthyEndpoint() {
    status.set(503);
    try (TestContainer container = new TestContainer()) {
      assertThrows(ContainerLaunchException.class, () -> container.awaitReady(target));
      assertTrue(requests.get() > 0);
    }
  }

  @Test
  void testWaitUntilEndpointRecovers() {
    initialFailures.set(1);
    try (TestContainer container = new TestContainer()) {
      assertDoesNotThrow(() -> container.awaitReady(target));
      assertTrue(requests.get() >= 2);
    }
  }

  @Test
  void testRejectUnreachablePublishedPort() {
    server.stop(0);
    try (TestContainer container = new TestContainer()) {
      assertThrows(ContainerLaunchException.class, () -> container.awaitReady(target));
      assertEquals(0, requests.get());
    }
  }

  @Test
  void testUrlUsesDockerHostAndMappedPort() {
    OpenSearchContainer container = mock(OpenSearchContainer.class);
    when(container.getHost()).thenReturn("docker.example.test");
    when(container.getPort()).thenReturn(32768);
    when(container.getOpenSearchUrl()).thenCallRealMethod();
    assertEquals("https://docker.example.test:32768", container.getOpenSearchUrl());
  }

  private static class TestContainer extends OpenSearchContainer {
    private TestContainer() {
      super("test-opensearch", Collections.emptyMap());
    }

    private void awaitReady(WaitStrategyTarget target) {
      getWaitStrategy().withStartupTimeout(Duration.ofSeconds(2)).waitUntilReady(target);
    }
  }
}
