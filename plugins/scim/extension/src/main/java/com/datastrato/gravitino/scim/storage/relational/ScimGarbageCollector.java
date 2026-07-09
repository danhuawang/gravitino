/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational;

import static org.apache.gravitino.Configs.GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT;
import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;

import com.datastrato.gravitino.scim.storage.service.ScimTokenMetaService;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Garbage collector for SCIM token metadata expiry and legacy purge. */
public final class ScimGarbageCollector implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(ScimGarbageCollector.class);
  private static final ScimTokenMetaService TOKEN_META_SERVICE = ScimTokenMetaService.getInstance();
  private static final long EXPIRY_TASK_FREQUENCY_MINUTES = 10;

  private final long storeDeleteAfterTimeMillis;

  private final ScheduledExecutorService garbageCollectorPool =
      new ScheduledThreadPoolExecutor(
          2,
          r -> {
            Thread t = new Thread(r, "Scim-Garbage-Collector");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /**
   * Creates a garbage collector for SCIM token metadata.
   *
   * @param config the server configuration
   */
  public ScimGarbageCollector(Config config) {
    storeDeleteAfterTimeMillis = config.get(STORE_DELETE_AFTER_TIME);
  }

  /** Starts scheduled expiry and legacy garbage collection tasks. */
  public void start() {
    long retentionMinutes = storeDeleteAfterTimeMillis / 1000 / 60;
    long legacyFrequencyMinutes = Math.max(retentionMinutes / 10, 10);
    garbageCollectorPool.scheduleAtFixedRate(
        this::collectAndClean, 5, legacyFrequencyMinutes, TimeUnit.MINUTES);
    garbageCollectorPool.scheduleAtFixedRate(
        this::softDeleteExpiredTokens, 5, EXPIRY_TASK_FREQUENCY_MINUTES, TimeUnit.MINUTES);
  }

  void collectAndClean() {
    long threadId = Thread.currentThread().getId();
    LOG.debug("Thread {} start to collect SCIM token legacy garbage...", threadId);
    try {
      long legacyTimeline = System.currentTimeMillis() - storeDeleteAfterTimeMillis;
      purgeLegacyData(
          () ->
              TOKEN_META_SERVICE.deleteTokenMetasByLegacyTimeline(legacyTimeline, deletionLimit()));
    } catch (Exception e) {
      LOG.error("Thread {} failed to collect and clean SCIM token legacy garbage.", threadId, e);
    } finally {
      LOG.debug("Thread {} finish to collect SCIM token legacy garbage.", threadId);
    }
  }

  void softDeleteExpiredTokens() {
    long threadId = Thread.currentThread().getId();
    LOG.debug("Thread {} start to soft-delete expired SCIM tokens...", threadId);
    try {
      int deleted = TOKEN_META_SERVICE.softDeleteExpiredScimTokens();
      if (deleted > 0) {
        LOG.info("Soft-deleted {} expired SCIM token rows", deleted);
      }
    } catch (Exception e) {
      LOG.error("Thread {} failed to soft-delete expired SCIM tokens.", threadId, e);
    } finally {
      LOG.debug("Thread {} finish soft-deleting expired SCIM tokens.", threadId);
    }
  }

  @Override
  public void close() throws IOException {
    garbageCollectorPool.shutdown();
    try {
      if (!garbageCollectorPool.awaitTermination(5, TimeUnit.SECONDS)) {
        garbageCollectorPool.shutdownNow();
      }
    } catch (InterruptedException ex) {
      garbageCollectorPool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static int deletionLimit() {
    return GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT;
  }

  private static void purgeLegacyData(LegacyDataDeleter deleter) {
    long deletedCount = Long.MAX_VALUE;
    try {
      while (deletedCount > 0) {
        deletedCount = deleter.delete();
      }
    } catch (RuntimeException e) {
      LOG.error("Failed to physically delete SCIM token legacy data", e);
    }
  }

  @FunctionalInterface
  private interface LegacyDataDeleter {
    int delete();
  }
}
