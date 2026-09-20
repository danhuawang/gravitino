/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codahale.metrics.Clock;
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jersey2.InstrumentedResourceMethodApplicationListener;
import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.dto.requests.CatalogWithTagsCreateRequest;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.tag.TagDispatcher;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Drives a real HTTP request through an internal REST endpoint and asserts that the counters behind
 * {@code /metrics} actually move.
 *
 * <p>{@code TestRestApiMetricsInstrumentation} checks that the annotations are present and {@code
 * TestRestApiMetricsExposure} checks that they reach the served registry, but neither one issues a
 * request. That left the case this module was reported for — an object created through the Web UI
 * not showing up in {@code /metrics} — unexercised by CI, so the regression was only ever caught by
 * hand on a running install.
 *
 * <p>The error case matters at least as much as the success case: a failed creation that increments
 * no meter cannot appear on the Monitor page at all, so the failure is invisible rather than merely
 * undercounted.
 */
public class TestInternalApiMetricsCounting extends JerseyTest {

  private static final String CREATE_CATALOG_METRIC =
      MetricsSource.GRAVITINO_SERVER_METRIC_NAME + ".internal-create-catalog";

  private static final class TestMetricsSource extends MetricsSource {
    private TestMetricsSource() {
      super(MetricsSource.GRAVITINO_SERVER_METRIC_NAME);
    }
  }

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  /**
   * Assigned in {@link #configure()} rather than initialized inline: {@code JerseyTest}'s
   * constructor calls {@code configure()} before the subclass field initializers run, so an inline
   * initializer would still be null there and would later overwrite whatever {@code configure()}
   * assigned.
   */
  private MetricsSystem metricsSystem;

  private final CatalogDispatcher catalogDispatcher = mock(CatalogManager.class);
  private final SchemaDispatcher schemaDispatcher = mock(DatastratoSchemaDispatcher.class);
  private final TableDispatcher tableDispatcher = mock(DatastratoTableDispatcher.class);
  private final FilesetDispatcher filesetDispatcher = mock(DatastratoFilesetDispatcher.class);
  private final TopicDispatcher topicDispatcher = mock(DatastratoTopicDispatcher.class);
  private final ModelDispatcher modelDispatcher = mock(DatastratoModelDispatcher.class);
  private final TagDispatcher tagDispatcher = mock(TagDispatcher.class);

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    metricsSystem = new MetricsSystem();
    MetricsSource metricsSource = new TestMetricsSource();
    ResourceConfig resourceConfig = new ResourceConfig();
    // Mirrors HttpServerMetricsSource: the listener meters the annotated resource methods into the
    // metrics source's own registry, which MetricsSystem exposes as a child of the served one.
    resourceConfig.register(
        new InstrumentedResourceMethodApplicationListener(
            metricsSource.getMetricRegistry(),
            Clock.defaultClock(),
            false,
            ExponentiallyDecayingReservoir::new));
    resourceConfig.register(CreationWithTagsOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
            bind(schemaDispatcher).to(SchemaDispatcher.class).ranked(2);
            bind(tableDispatcher).to(TableDispatcher.class).ranked(2);
            bind(filesetDispatcher).to(FilesetDispatcher.class).ranked(2);
            bind(topicDispatcher).to(TopicDispatcher.class).ranked(2);
            bind(modelDispatcher).to(ModelDispatcher.class).ranked(2);
            bind(tagDispatcher).to(TagDispatcher.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });

    // As in GravitinoServer#initializeRestApi, the source is registered before Jersey initializes
    // the application, so the metrics created during initialization must still propagate upwards.
    metricsSystem.register(metricsSource);
    return resourceConfig;
  }

  @Test
  public void testSuccessfulCreationIncrementsTheSuccessCounters() {
    when(catalogDispatcher.createCatalog(any(), any(), any(), any(), any()))
        .thenReturn(buildCatalog("catalog1"));

    long durationsBefore = timerCount();
    long successesBefore = meterCount("2xx-responses");

    Response resp = createCatalog("catalog1");

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    awaitCount(
        this::timerCount,
        durationsBefore + 1,
        "A successful creation did not update " + CREATE_CATALOG_METRIC);
    awaitCount(
        () -> meterCount("2xx-responses"),
        successesBefore + 1,
        "A successful creation did not increment the 2xx meter");
  }

  @Test
  public void testFailedCreationIncrementsTheErrorCounter() {
    doThrow(new CatalogAlreadyExistsException("mock error"))
        .when(catalogDispatcher)
        .createCatalog(any(), any(), any(), any(), any());

    long errorsBefore = meterCount("4xx-responses");
    long successesBefore = meterCount("2xx-responses");

    Response resp = createCatalog("catalog2");

    Assertions.assertEquals(CONFLICT.getStatusCode(), resp.getStatus());
    awaitCount(
        () -> meterCount("4xx-responses"),
        errorsBefore + 1,
        "A failed creation did not increment the 4xx meter, so it cannot appear on the Monitor "
            + "page at all");
    // Safe to read directly: the 4xx meter for this request has already been recorded above, and
    // the two meters are updated by the same event.
    Assertions.assertEquals(
        successesBefore, meterCount("2xx-responses"), "A failed creation moved the 2xx meter");
  }

  private Response createCatalog(String name) {
    CatalogWithTagsCreateRequest req =
        new CatalogWithTagsCreateRequest(
            name,
            Catalog.Type.RELATIONAL,
            "test",
            "comment",
            ImmutableMap.of("key", "value"),
            null);

    return target("/internal/with-tags/metalakes/metalake1/catalogs")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .post(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE));
  }

  /**
   * Waits for a counter to reach {@code expected} before asserting on it.
   *
   * <p>The response meters are updated from Jersey's {@code RequestEvent.FINISHED}, which fires
   * after the response has been written, so the client can return from the call before the server
   * has finished its bookkeeping. Reading the counter straight after the response is a race that
   * passes on a fast machine and fails under CI load.
   */
  private static void awaitCount(LongSupplier actual, long expected, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    long seen = actual.getAsLong();
    while (seen != expected && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      seen = actual.getAsLong();
    }

    Assertions.assertEquals(expected, seen, message);
  }

  private long timerCount() {
    String name = CREATE_CATALOG_METRIC + "." + MetricNames.HTTP_PROCESS_DURATION;
    Timer timer = metricsSystem.getMetricRegistry().getTimers().get(name);
    Assertions.assertNotNull(timer, "Timer " + name + " is not registered");
    return timer.getCount();
  }

  private long meterCount(String responseClass) {
    String name = CREATE_CATALOG_METRIC + "." + responseClass;
    Meter meter = metricsSystem.getMetricRegistry().getMeters().get(name);
    Assertions.assertNotNull(meter, "Meter " + name + " is not registered");
    return meter.getCount();
  }

  private static TestCatalog buildCatalog(String catalogName) {
    CatalogEntity entity =
        CatalogEntity.builder()
            .withId(1L)
            .withName(catalogName)
            .withComment("comment")
            .withNamespace(Namespace.of("metalake"))
            .withProperties(ImmutableMap.of("key", "value"))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();

    return new TestCatalog().withCatalogConf(Collections.emptyMap()).withCatalogEntity(entity);
  }
}
