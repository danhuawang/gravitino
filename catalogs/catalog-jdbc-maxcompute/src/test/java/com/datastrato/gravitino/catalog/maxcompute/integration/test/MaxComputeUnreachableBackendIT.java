/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.integration.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * End-to-end integration test proving that a MaxCompute catalog pointed at an unreachable backend
 * fails within a bounded time instead of hanging, which is what the injected ODPS connection
 * parameters (notably {@code retryTime}) guarantee.
 *
 * <p>This reproduces the shape of the reported incident where the search metadata sync blocked
 * inside the ODPS driver for about fifteen minutes while holding the metalake tree locks. Here we
 * assert the opposite: a metadata operation against a dead endpoint returns (with an error) well
 * inside the tree-lock dead-lock checker window.
 *
 * <p>The ODPS JDBC driver is not bundled with Gravitino (operators supply it), so this test is
 * skipped unless it is explicitly enabled and the driver is on the classpath. To run it locally,
 * place {@code odps-jdbc-*-jar-with-dependencies.jar} on the test runtime classpath and set:
 *
 * <pre>
 * export MAXCOMPUTE_UNREACHABLE_IT=true
 * </pre>
 */
@TestInstance(Lifecycle.PER_CLASS)
@EnabledIf("isEnabled")
public class MaxComputeUnreachableBackendIT extends BaseIT {

  private static final String PROVIDER = "jdbc-maxcompute";
  private static final String DRIVER_CLASS_NAME = "com.aliyun.odps.jdbc.OdpsDriver";

  /** A routable address with nothing listening, so a connection attempt would otherwise hang. */
  private static final String UNREACHABLE_URL =
      "jdbc:odps:http://127.0.0.1:1/api?project=probe&odpsNamespaceSchema=true";

  /**
   * Upper bound for a single metadata operation, aligned with the 30s tree-lock dead-lock checker
   * window that this fix is meant to stay inside. The injected defaults cap the driver at roughly
   * twelve seconds against a dead endpoint, so this bound holds with margin; a regression that let
   * the operation approach the fifteen-minute hang would fail it.
   */
  private static final long MAX_ALLOWED_SECONDS = 30;

  private final String metalakeName = GravitinoITUtils.genRandomName("mc_unreachable_metalake");
  private final String catalogName = GravitinoITUtils.genRandomName("mc_unreachable_catalog");

  private GravitinoMetalake metalake;

  // Invoked by @EnabledIf via reflection.
  @SuppressWarnings("unused")
  static boolean isEnabled() {
    if (!"true".equalsIgnoreCase(System.getenv("MAXCOMPUTE_UNREACHABLE_IT"))) {
      return false;
    }
    return isDriverPresent();
  }

  private static boolean isDriverPresent() {
    try {
      Class.forName(DRIVER_CLASS_NAME, false, Thread.currentThread().getContextClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @BeforeAll
  public void startup() {
    client.createMetalake(metalakeName, "unreachable-backend IT", Collections.emptyMap());
    metalake = client.loadMetalake(metalakeName);
    assertNotNull(metalake);
  }

  @AfterAll
  public void stop() {
    if (metalake != null) {
      try {
        metalake.disableCatalog(catalogName);
        metalake.dropCatalog(catalogName);
      } catch (Exception ignored) {
        // best-effort cleanup
      }
      try {
        client.disableMetalake(metalakeName);
        client.dropMetalake(metalakeName);
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
  }

  @Test
  void testUnreachableBackendFailsFastInsteadOfHanging() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(JdbcConfig.JDBC_URL.getKey(), UNREACHABLE_URL);
    properties.put(JdbcConfig.JDBC_DRIVER.getKey(), DRIVER_CLASS_NAME);
    properties.put(JdbcConfig.USERNAME.getKey(), "probe");
    properties.put(JdbcConfig.PASSWORD.getKey(), "probe");

    Catalog catalog =
        metalake.createCatalog(
            catalogName,
            Catalog.Type.RELATIONAL,
            PROVIDER,
            "unreachable MaxCompute backend",
            properties);
    assertNotNull(catalog);

    // Listing schemas forces a real connection to the (dead) backend. It must return with an error
    // inside the bound, not block. A watchdog thread converts a hang into a clear failure instead
    // of
    // stalling the whole suite for minutes.
    long start = System.nanoTime();
    long elapsedSeconds = runBounded(() -> catalog.asSchemas().listSchemas());
    long observed = (System.nanoTime() - start) / 1_000_000_000L;

    if (elapsedSeconds < 0) {
      fail(
          "listSchemas against an unreachable MaxCompute backend did not return within "
              + MAX_ALLOWED_SECONDS
              + "s; the connection timeout injection is not bounding the driver.");
    }
    assertTrue(
        observed <= MAX_ALLOWED_SECONDS,
        "listSchemas took " + observed + "s, expected <= " + MAX_ALLOWED_SECONDS + "s");
  }

  /**
   * Runs the operation under a watchdog. Returns the elapsed seconds if it completed (whether it
   * succeeded or threw), or -1 if it was still running after {@link #MAX_ALLOWED_SECONDS} and had
   * to be abandoned (treated as a hang).
   */
  private long runBounded(Callable<?> operation) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    long start = System.nanoTime();
    Future<?> future =
        executor.submit(
            () -> {
              try {
                operation.call();
              } catch (Exception expected) {
                // A connection error is the expected outcome; we only care about timing here.
              }
              return null;
            });
    try {
      future.get(MAX_ALLOWED_SECONDS, TimeUnit.SECONDS);
      return (System.nanoTime() - start) / 1_000_000_000L;
    } catch (TimeoutException e) {
      future.cancel(true);
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    } catch (ExecutionException e) {
      return (System.nanoTime() - start) / 1_000_000_000L;
    } finally {
      executor.shutdownNow();
    }
  }
}
