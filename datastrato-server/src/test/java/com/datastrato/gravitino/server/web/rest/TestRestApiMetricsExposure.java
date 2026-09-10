/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codahale.metrics.Clock;
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.jersey2.InstrumentedResourceMethodApplicationListener;
import java.util.Set;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the annotations on the enterprise REST resources actually reach the registry served
 * by {@code /metrics}.
 *
 * <p>The production wiring has three hops: {@code HttpServerMetricsSource} registers a Jersey
 * listener against the metrics source's own registry, {@code MetricsSystem} registers that registry
 * as a child of the registry {@code MetricsServlet} serves, and Jersey creates the metrics when the
 * application starts. The child registry is registered <em>before</em> the Jersey application is
 * initialized, so metrics created later must still propagate to the parent; this test reproduces
 * that ordering.
 */
public class TestRestApiMetricsExposure {

  private static final class TestMetricsSource extends MetricsSource {
    private TestMetricsSource() {
      super(MetricsSource.GRAVITINO_SERVER_METRIC_NAME);
    }
  }

  @Test
  public void testCreationEndpointMetricsReachTheServedRegistry() {
    MetricsSystem metricsSystem = new MetricsSystem();
    MetricsSource metricsSource = new TestMetricsSource();

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(
        new InstrumentedResourceMethodApplicationListener(
            metricsSource.getMetricRegistry(),
            Clock.defaultClock(),
            false,
            ExponentiallyDecayingReservoir::new));
    resourceConfig.register(CreationWithTagsOperations.class);

    // Mirrors GravitinoServer#initializeRestApi: the source is registered first, the Jersey
    // application is initialized afterwards.
    metricsSystem.register(metricsSource);
    new ApplicationHandler(resourceConfig);

    Set<String> names = metricsSystem.getMetricRegistry().getNames();
    String prefix = MetricsSource.GRAVITINO_SERVER_METRIC_NAME + ".web-create-catalog";

    assertTrue(
        names.contains(prefix + "." + MetricNames.HTTP_PROCESS_DURATION),
        "The create-catalog timer is missing from the served registry, found: " + names);
    for (String responseClass : new String[] {"2xx-responses", "4xx-responses", "5xx-responses"}) {
      assertTrue(
          names.contains(prefix + "." + responseClass),
          "The create-catalog " + responseClass + " meter is missing, found: " + names);
    }
  }
}
