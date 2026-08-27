/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import static org.apache.gravitino.Configs.GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT;
import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;

import com.google.common.annotations.VisibleForTesting;
import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.gravitino.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodically removes persisted connection test results for Catalogs that are no longer live. */
public final class CatalogConnectionTestGarbageCollector implements Closeable {

  private static final Logger LOG =
      LoggerFactory.getLogger(CatalogConnectionTestGarbageCollector.class);
  private static final long INITIAL_DELAY_MINUTES = 5;
  private static final long MINIMUM_FREQUENCY_MINUTES = 10;

  private final ConnectionTestStore connectionTestStore;
  private final long frequencyMinutes;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean started = new AtomicBoolean(false);

  /**
   * Creates a background collector using the relational store retention configuration.
   *
   * @param connectionTestStore The persistent connection test store.
   * @param config The server configuration.
   */
  public CatalogConnectionTestGarbageCollector(
      ConnectionTestStore connectionTestStore, Config config) {
    this.connectionTestStore = connectionTestStore;
    long retentionMinutes = config.get(STORE_DELETE_AFTER_TIME) / 1000 / 60;
    this.frequencyMinutes = Math.max(retentionMinutes / 10, MINIMUM_FREQUENCY_MINUTES);
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "Catalog-Connection-Test-Garbage-Collector");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Starts periodic orphan cleanup. */
  public void start() {
    if (started.compareAndSet(false, true)) {
      executor.scheduleAtFixedRate(
          this::collectAndClean, INITIAL_DELAY_MINUTES, frequencyMinutes, TimeUnit.MINUTES);
    }
  }

  @VisibleForTesting
  void collectAndClean() {
    try {
      int totalDeleted = 0;
      int deleted;
      do {
        deleted =
            connectionTestStore.deleteOrphanedTestResults(GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT);
        totalDeleted += deleted;
      } while (deleted > 0);

      if (totalDeleted > 0) {
        LOG.info("Deleted {} orphaned connection test results", totalDeleted);
      }
    } catch (RuntimeException e) {
      LOG.error("Failed to delete orphaned connection test results", e);
    }
  }

  /** Stops periodic orphan cleanup. */
  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
