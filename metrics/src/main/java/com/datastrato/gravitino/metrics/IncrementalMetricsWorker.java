/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.Closeable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Polls durable dirty markers and schedules per-metalake dashboard metric recomputation. */
public class IncrementalMetricsWorker implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(IncrementalMetricsWorker.class);
  private static final int MAX_ERROR_LENGTH = 1024;

  private final MetricsCollector metricsCollector;
  private final MetricDataService metricDataService;
  private final Set<Long> inFlightMetalakes = ConcurrentHashMap.newKeySet();

  private ScheduledThreadPoolExecutor pollExecutor;
  private long pollIntervalMs;
  private long debounceMs;
  private long maxDebounceMs;
  private long retryInitialMs;
  private long retryMaxMs;

  IncrementalMetricsWorker(MetricsCollector metricsCollector, MetricDataService metricDataService) {
    this.metricsCollector = metricsCollector;
    this.metricDataService = metricDataService;
  }

  /**
   * Initializes worker timing and its dedicated polling executor.
   *
   * @param serverConfig metrics service configuration
   */
  public void initialize(ServerConfig serverConfig) {
    pollIntervalMs = serverConfig.get(MetricsConfig.INCREMENTAL_POLL_INTERVAL_MS_CONFIG);
    debounceMs = serverConfig.get(MetricsConfig.INCREMENTAL_DEBOUNCE_MS_CONFIG);
    maxDebounceMs = serverConfig.get(MetricsConfig.INCREMENTAL_MAX_DEBOUNCE_MS_CONFIG);
    retryInitialMs = serverConfig.get(MetricsConfig.INCREMENTAL_RETRY_INITIAL_MS_CONFIG);
    retryMaxMs = serverConfig.get(MetricsConfig.INCREMENTAL_RETRY_MAX_MS_CONFIG);

    if (maxDebounceMs < debounceMs) {
      throw new IllegalArgumentException(
          "Dashboard metric max debounce must be greater than or equal to debounce");
    }
    if (retryMaxMs < retryInitialMs) {
      throw new IllegalArgumentException(
          "Dashboard metric maximum retry delay must be greater than or equal to initial delay");
    }

    pollExecutor =
        new ScheduledThreadPoolExecutor(
            1,
            new ThreadFactoryBuilder()
                .setNameFormat("incremental-metrics-poller-%d")
                .setDaemon(true)
                .build());
    pollExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
  }

  /** Starts polling durable dashboard metric dirty markers. */
  public void start() {
    pollExecutor.scheduleWithFixedDelay(this::pollSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    LOG.info("Incremental dashboard metrics worker started");
  }

  /** Stops accepting new dirty markers; in-flight calculations finish in the collector. */
  @Override
  public void close() {
    if (pollExecutor == null) {
      return;
    }
    pollExecutor.shutdown();
    try {
      if (!pollExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        LOG.warn("Incremental dashboard metrics poller did not terminate within 10 seconds");
        pollExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      pollExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  void pollOnce() {
    long now = System.currentTimeMillis();
    List<MetricDirtyPO> dueMetalakes =
        metricDataService.listDueDirtyMetalakes(now - debounceMs, now - maxDebounceMs, now);
    for (MetricDirtyPO dirty : dueMetalakes) {
      long metalakeId = dirty.getMetalakeId();
      if (!inFlightMetalakes.add(metalakeId)) {
        continue;
      }
      try {
        metricsCollector
            .submitIncremental(() -> processDirtyMetalake(metalakeId))
            .whenComplete(
                (ignored, failure) -> {
                  inFlightMetalakes.remove(metalakeId);
                  if (failure != null) {
                    LOG.warn(
                        "Incremental dashboard metric task failed for metalake {}",
                        metalakeId,
                        failure);
                  }
                });
      } catch (RuntimeException e) {
        inFlightMetalakes.remove(metalakeId);
        throw e;
      }
    }
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (Exception e) {
      LOG.warn("Failed to poll dirty dashboard metrics", e);
    }
  }

  private void processDirtyMetalake(long metalakeId) {
    synchronized (metricsCollector.metalakeLock(metalakeId)) {
      MetricDirtyPO dirty = metricDataService.getDirtyMetalake(metalakeId);
      if (dirty == null || !isDue(dirty, System.currentTimeMillis())) {
        return;
      }

      long revision = dirty.getRevision();
      try {
        Optional<BaseMetalake> metalake = metricsCollector.findActiveMetalake(metalakeId);
        if (metalake.isEmpty()) {
          metricDataService.deleteDirtyIfRevision(metalakeId, revision);
          return;
        }

        MetricsCollector.CollectionOutcome outcome =
            metricsCollector.refreshAndPublishDirtyMetalake(
                metalake.get(),
                MetricsCollector.PublishMode.CURRENT_ONLY,
                System.currentTimeMillis());
        if (outcome == MetricsCollector.CollectionOutcome.COMPLETE) {
          metricDataService.deleteDirtyIfRevision(metalakeId, revision);
        } else {
          scheduleRetry(dirty, "Dashboard metric collection is incomplete", null);
        }
      } catch (Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        scheduleRetry(dirty, message, e);
      }
    }
  }

  private boolean isDue(MetricDirtyPO dirty, long now) {
    if (dirty.getRetryAfter() != null) {
      return dirty.getRetryAfter().getTime() <= now;
    }
    return dirty.getLastEventAt().getTime() <= now - debounceMs
        || dirty.getFirstDirtyAt().getTime() <= now - maxDebounceMs;
  }

  private void scheduleRetry(
      MetricDirtyPO dirty, String failureMessage, @Nullable Exception failure) {
    int retryCount =
        dirty.getRetryCount() == Integer.MAX_VALUE ? Integer.MAX_VALUE : dirty.getRetryCount() + 1;
    long exponentialDelay = retryInitialMs;
    for (int i = 1; i < retryCount && exponentialDelay < retryMaxMs; i++) {
      exponentialDelay = exponentialDelay > retryMaxMs / 2 ? retryMaxMs : exponentialDelay * 2;
    }
    long jitterBound = Math.max(1, exponentialDelay / 5);
    long jitter = ThreadLocalRandom.current().nextLong(-jitterBound, jitterBound + 1);
    long retryDelay = Math.min(retryMaxMs, Math.max(1, exponentialDelay + jitter));
    long retryAfter = System.currentTimeMillis() + retryDelay;
    String truncatedError =
        failureMessage.length() <= MAX_ERROR_LENGTH
            ? failureMessage
            : failureMessage.substring(0, MAX_ERROR_LENGTH);
    metricDataService.markRetryIfRevision(
        dirty.getMetalakeId(), dirty.getRevision(), retryCount, retryAfter, truncatedError);
    if (failure == null) {
      LOG.warn(
          "Dashboard metrics remain incomplete for metalake {}; retry {} scheduled",
          dirty.getMetalakeId(),
          retryCount);
    } else {
      LOG.warn(
          "Failed to recompute dashboard metrics for metalake {}, retry {} scheduled",
          dirty.getMetalakeId(),
          retryCount,
          failure);
    }
  }
}
