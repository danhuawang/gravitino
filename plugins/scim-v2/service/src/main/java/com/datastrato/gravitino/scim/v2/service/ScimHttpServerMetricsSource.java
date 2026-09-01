/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service;

import com.codahale.metrics.Clock;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.jersey2.InstrumentedResourceMethodApplicationListener;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.metrics.source.MetricsSource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.glassfish.jersey.server.ResourceConfig;

/** Registers HTTP and thread-pool metrics for the SCIM auxiliary listener. */
final class ScimHttpServerMetricsSource extends MetricsSource {

  /**
   * Creates and registers Jersey resource and Jetty thread-pool metrics.
   *
   * @param name metrics source name
   * @param resourceConfig Jersey application configuration
   * @param server SCIM embedded Jetty server
   */
  ScimHttpServerMetricsSource(String name, ResourceConfig resourceConfig, ScimJettyServer server) {
    super(name);
    resourceConfig.register(
        new InstrumentedResourceMethodApplicationListener(
            getMetricRegistry(),
            Clock.defaultClock(),
            false,
            () ->
                new SlidingTimeWindowArrayReservoir(
                    getTimeSlidingWindowSeconds(), TimeUnit.SECONDS)));

    ThreadPool threadPool = server.getThreadPool();
    registerGauge(MetricNames.SERVER_IDLE_THREAD_NUM, threadPool::getIdleThreads);
    registerGauge(MetricNames.SERVER_TOTAL_THREAD_NUM, threadPool::getThreads);

    if (threadPool instanceof QueuedThreadPool queuedThreadPool) {
      registerGauge(MetricNames.SERVER_BUSY_THREAD_NUM, queuedThreadPool::getBusyThreads);
      registerGauge(MetricNames.SERVER_QUEUED_REQUEST_NUM, queuedThreadPool::getQueueSize);
      registerGauge(MetricNames.SERVER_MIN_THREAD_NUM, queuedThreadPool::getMinThreads);
      registerGauge(MetricNames.SERVER_MAX_THREAD_NUM, queuedThreadPool::getMaxThreads);
    }
  }
}
