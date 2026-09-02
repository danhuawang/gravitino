/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.relational;

import static org.apache.gravitino.Configs.GARBAGE_COLLECTOR_SINGLE_DELETION_LIMIT;
import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;

import com.datastrato.gravitino.scim.ScimConfigs;
import com.datastrato.gravitino.scim.storage.service.ScimErrorHistoryMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimGroupMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimTokenMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimUserGroupRelMetaService;
import com.datastrato.gravitino.scim.storage.service.ScimUserMetaService;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Garbage collector for SCIM metadata expiry and purge. */
public final class ScimGarbageCollector implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(ScimGarbageCollector.class);
  private static final ScimTokenMetaService TOKEN_META_SERVICE = ScimTokenMetaService.getInstance();
  private static final ScimUserGroupRelMetaService USER_GROUP_REL_META_SERVICE =
      ScimUserGroupRelMetaService.getInstance();
  private static final ScimUserMetaService USER_META_SERVICE = ScimUserMetaService.getInstance();
  private static final ScimGroupMetaService GROUP_META_SERVICE = ScimGroupMetaService.getInstance();
  private static final ScimErrorHistoryMetaService ERROR_HISTORY_META_SERVICE =
      ScimErrorHistoryMetaService.getInstance();
  private static final long EXPIRY_TASK_FREQUENCY_MINUTES = 10;
  private static final long ERROR_HISTORY_CLEAN_INITIAL_DELAY_MINUTES = 5;
  private static final long ERROR_HISTORY_CLEAN_PERIOD_DAYS = 1;

  private final long storeDeleteAfterTimeMillis;
  private final long errorHistoryRetentionMillis;

  private final ScheduledExecutorService garbageCollectorPool =
      new ScheduledThreadPoolExecutor(
          2,
          r -> {
            Thread t = new Thread(r, "Scim-Garbage-Collector");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private final ScheduledExecutorService errorHistoryCleaner =
      new ScheduledThreadPoolExecutor(
          1,
          r -> {
            Thread t = new Thread(r, "Scim-Error-History-Cleaner");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /** Creates a garbage collector for SCIM metadata. */
  public ScimGarbageCollector(Config config) {
    storeDeleteAfterTimeMillis = config.get(STORE_DELETE_AFTER_TIME);
    errorHistoryRetentionMillis =
        TimeUnit.DAYS.toMillis(config.get(ScimConfigs.ERROR_HISTORY_RETENTION_DAYS));
  }

  /** Starts scheduled expiry, legacy purge, and daily error-history cleanup tasks. */
  public void start() {
    long retentionMinutes = storeDeleteAfterTimeMillis / 1000 / 60;
    long legacyFrequencyMinutes = Math.max(retentionMinutes / 10, 10);
    garbageCollectorPool.scheduleAtFixedRate(
        this::collectAndClean, 5, legacyFrequencyMinutes, TimeUnit.MINUTES);
    garbageCollectorPool.scheduleAtFixedRate(
        this::softDeleteExpiredTokens, 5, EXPIRY_TASK_FREQUENCY_MINUTES, TimeUnit.MINUTES);
    garbageCollectorPool.scheduleAtFixedRate(
        this::softDeleteOrphanMemberships, 5, EXPIRY_TASK_FREQUENCY_MINUTES, TimeUnit.MINUTES);
    errorHistoryCleaner.scheduleAtFixedRate(
        this::cleanExpiredErrorHistory,
        ERROR_HISTORY_CLEAN_INITIAL_DELAY_MINUTES,
        TimeUnit.DAYS.toMinutes(ERROR_HISTORY_CLEAN_PERIOD_DAYS),
        TimeUnit.MINUTES);
  }

  void collectAndClean() {
    long threadId = Thread.currentThread().getId();
    LOG.debug("Thread {} start to collect SCIM legacy garbage...", threadId);
    try {
      long legacyTimeline = System.currentTimeMillis() - storeDeleteAfterTimeMillis;
      purgeLegacyData(
          () ->
              USER_GROUP_REL_META_SERVICE.deleteScimUserGroupRelMetasByLegacyTimeline(
                  legacyTimeline, deletionLimit()));
      purgeLegacyData(
          () ->
              USER_META_SERVICE.deleteScimUserMetasByLegacyTimeline(
                  legacyTimeline, deletionLimit()));
      purgeLegacyData(
          () ->
              GROUP_META_SERVICE.deleteScimGroupMetasByLegacyTimeline(
                  legacyTimeline, deletionLimit()));
      purgeLegacyData(
          () ->
              TOKEN_META_SERVICE.deleteTokenMetasByLegacyTimeline(legacyTimeline, deletionLimit()));
    } catch (Exception e) {
      LOG.error("Thread {} failed to collect and clean SCIM legacy garbage.", threadId, e);
    } finally {
      LOG.debug("Thread {} finish to collect SCIM legacy garbage.", threadId);
    }
  }

  void cleanExpiredErrorHistory() {
    long threadId = Thread.currentThread().getId();
    LOG.debug("Thread {} start to clean expired SCIM error history...", threadId);
    try {
      long cutoff = System.currentTimeMillis() - errorHistoryRetentionMillis;
      purgeLegacyData(
          () ->
              ERROR_HISTORY_META_SERVICE.deleteScimErrorHistoryByCreatedAtBefore(
                  cutoff, deletionLimit()));
    } catch (Exception e) {
      LOG.error("Thread {} failed to clean expired SCIM error history.", threadId, e);
    } finally {
      LOG.debug("Thread {} finish cleaning expired SCIM error history.", threadId);
    }
  }

  void softDeleteExpiredTokens() {
    long threadId = Thread.currentThread().getId();
    LOG.debug("Thread {} start to soft-delete expired SCIM tokens...", threadId);
    try {
      int expiredDeleted = TOKEN_META_SERVICE.softDeleteExpiredScimTokens();
      if (expiredDeleted > 0) {
        LOG.info("Soft-deleted {} expired SCIM token rows", expiredDeleted);
      }
    } catch (Exception e) {
      LOG.error("Thread {} failed to soft-delete expired SCIM tokens.", threadId, e);
    } finally {
      LOG.debug("Thread {} finish soft-deleting expired SCIM tokens.", threadId);
    }
  }

  /** Soft-deletes membership rows whose user or group is soft-deleted. */
  void softDeleteOrphanMemberships() {
    long threadId = Thread.currentThread().getId();
    LOG.debug("Thread {} start to soft-delete orphan SCIM memberships...", threadId);
    try {
      int deleted = USER_GROUP_REL_META_SERVICE.softDeleteOrphanMemberships();
      if (deleted > 0) {
        LOG.info("Soft-deleted {} orphan SCIM membership rows", deleted);
      }
    } catch (Exception e) {
      LOG.error("Thread {} failed to soft-delete orphan SCIM memberships.", threadId, e);
    } finally {
      LOG.debug("Thread {} finish soft-deleting orphan SCIM memberships.", threadId);
    }
  }

  @Override
  public void close() throws IOException {
    shutdown(garbageCollectorPool);
    shutdown(errorHistoryCleaner);
  }

  private static void shutdown(ScheduledExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException ex) {
      pool.shutdownNow();
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
      LOG.error("Failed to physically delete SCIM legacy data", e);
    }
  }

  @FunctionalInterface
  private interface LegacyDataDeleter {
    int delete();
  }
}
