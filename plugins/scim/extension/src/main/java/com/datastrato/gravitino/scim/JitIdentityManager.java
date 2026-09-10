/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import com.datastrato.gravitino.scim.storage.service.JitIdentityMetaService;
import com.google.common.base.Preconditions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.UserGroup;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.storage.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronously records identities discovered from validated JWTs.
 *
 * <p>The queue is bounded and single-threaded so snapshots for one process are applied in token
 * order without blocking authentication threads. Under sustained overload the oldest queued
 * snapshot is discarded in favor of the newest observation.
 */
public class JitIdentityManager {
  private static final Logger LOG = LoggerFactory.getLogger(JitIdentityManager.class);
  private static final int RECORD_QUEUE_CAPACITY = 1024;
  private static final int MAX_DUPLICATE_RETRIES = 1;
  private static final ExecutorService RECORDER =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(RECORD_QUEUE_CAPACITY),
          runnable -> {
            Thread thread = new Thread(runnable, "Jit-Identity-Recorder");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.DiscardOldestPolicy());

  private static final class InstanceHolder {
    private static final JitIdentityManager INSTANCE =
        new JitIdentityManager(JitIdentityMetaService::getInstance);
  }

  private final Supplier<JitIdentityMetaService> metaServiceSupplier;
  private volatile IdGenerator idGenerator;

  /** Returns the process-wide JIT identity manager. */
  public static JitIdentityManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  JitIdentityManager(Supplier<JitIdentityMetaService> metaServiceSupplier) {
    this.metaServiceSupplier = metaServiceSupplier;
  }

  /** Binds the global id generator used by asynchronous persistence. */
  public synchronized void initialize(IdGenerator idGenerator) {
    this.idGenerator = Preconditions.checkNotNull(idGenerator, "idGenerator must not be null");
  }

  /**
   * Enqueues one validated JWT identity snapshot. This method never throws to authentication.
   *
   * @param userName mapped username
   * @param groups mapped JWT groups
   */
  public void recordAsync(String userName, List<UserGroup> groups) {
    try {
      if (StringUtils.isBlank(userName)) {
        return;
      }
      List<String> groupNames =
          groups == null
              ? List.of()
              : groups.stream()
                  .map(UserGroup::getGroupName)
                  .filter(StringUtils::isNotBlank)
                  .distinct()
                  .toList();
      long observedAt = Instant.now().toEpochMilli();
      String auditInfo =
          ScimPOConverters.serializeAuditInfo(
              AuditInfo.builder()
                  .withCreator(userName)
                  .withCreateTime(Instant.ofEpochMilli(observedAt))
                  .build());
      RECORDER.execute(() -> record(userName, groupNames, observedAt, auditInfo));
    } catch (Exception e) {
      LOG.warn("Failed to enqueue JIT identity snapshot for user {}", userName, e);
    }
  }

  private void record(String userName, List<String> groupNames, long observedAt, String auditInfo) {
    IdGenerator generator = idGenerator;
    if (generator == null) {
      LOG.warn("Skip JIT identity snapshot; id generator is not initialized");
      return;
    }
    try {
      synchronizeWithRetry(userName, groupNames, observedAt, auditInfo, generator);
    } catch (Exception e) {
      LOG.warn("Failed to record JIT identity snapshot for user {}", userName, e);
    }
  }

  private void synchronizeWithRetry(
      String userName,
      List<String> groupNames,
      long observedAt,
      String auditInfo,
      IdGenerator generator) {
    for (int attempt = 0; ; attempt++) {
      try {
        metaServiceSupplier
            .get()
            .synchronize(userName, groupNames, observedAt, auditInfo, generator);
        return;
      } catch (RuntimeException e) {
        if (attempt >= MAX_DUPLICATE_RETRIES || !ScimExceptionUtils.isDuplicateEntry(e)) {
          throw e;
        }
      }
    }
  }
}
