/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import com.datastrato.gravitino.scim.storage.po.ScimErrorHistoryPO;
import com.datastrato.gravitino.scim.storage.service.ScimErrorHistoryMetaService;
import com.google.common.base.Preconditions;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.storage.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records failed IdP-facing SCIM protocol calls (port 9201 Users/Groups) into {@code
 * scim_error_history}.
 *
 * <p>Token admin APIs on the main server are out of scope. HTTP 404 responses (including user or
 * group not found) are not recorded.
 */
public final class ScimErrorHistoryManager {

  private static final Logger LOG = LoggerFactory.getLogger(ScimErrorHistoryManager.class);
  private static final ScimErrorHistoryMetaService ERROR_HISTORY_META_SERVICE =
      ScimErrorHistoryMetaService.getInstance();
  private static final int MAX_PATH_LENGTH = 1024;
  private static final int MAX_DETAIL_LENGTH = 1024;
  private static final int MAX_PRINCIPAL_LENGTH = 256;
  private static final int RECORD_QUEUE_CAPACITY = 256;
  private static final ExecutorService RECORDER =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(RECORD_QUEUE_CAPACITY),
          runnable -> {
            Thread thread = new Thread(runnable, "Scim-Error-History");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.DiscardPolicy());
  private static final Pattern USERS_OR_GROUPS_PATH =
      Pattern.compile(
          "^"
              + Pattern.quote(ScimUtils.METALAKE_SCIM_PREFIX)
              + "[^/]+/(Users|Groups)(?:/[^/]+)?/?$");

  private static final class InstanceHolder {
    private static final ScimErrorHistoryManager INSTANCE = new ScimErrorHistoryManager();
  }

  private volatile IdGenerator idGenerator;

  /** Returns the shared SCIM error history manager. */
  public static ScimErrorHistoryManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  ScimErrorHistoryManager() {}

  /**
   * Binds the id generator used when recording failures.
   *
   * @param idGenerator the id generator
   */
  public synchronized void initialize(IdGenerator idGenerator) {
    Preconditions.checkNotNull(idGenerator, "idGenerator must not be null");
    this.idGenerator = idGenerator;
  }

  /**
   * Records a failed metalake-scoped SCIM protocol request. Never throws to the HTTP path.
   *
   * @param metalakeName metalake name from the request path, or blank when unknown
   * @param httpMethod HTTP method
   * @param requestPath request URI
   * @param httpStatus HTTP status code
   * @param scimType optional RFC 7644 {@code scimType}
   * @param errorDetail optional truncated error detail
   * @param principal authenticated SCIM token name, or blank when unknown
   */
  public void recordHttpFailure(
      @Nullable String metalakeName,
      String httpMethod,
      String requestPath,
      int httpStatus,
      @Nullable String scimType,
      @Nullable String errorDetail,
      @Nullable String principal) {
    try {
      if (!shouldRecord(httpStatus, requestPath)) {
        return;
      }
      RECORDER.execute(
          () -> {
            try {
              IdGenerator generator = idGenerator;
              if (generator == null) {
                LOG.warn("Skip SCIM error history; id generator is not initialized");
                return;
              }
              ERROR_HISTORY_META_SERVICE.insertScimErrorHistory(
                  ScimErrorHistoryPO.builder()
                      .withErrorId(generator.nextId())
                      .withMetalakeId(0L)
                      .withMetalakeName(StringUtils.defaultString(metalakeName))
                      .withHttpMethod(
                          StringUtils.defaultString(httpMethod).toUpperCase(Locale.ROOT))
                      .withRequestPath(
                          StringUtils.truncate(
                              StringUtils.defaultString(requestPath), MAX_PATH_LENGTH))
                      .withHttpStatus(httpStatus)
                      .withScimType(StringUtils.trimToNull(scimType))
                      .withErrorDetail(
                          StringUtils.truncate(
                              StringUtils.defaultString(errorDetail), MAX_DETAIL_LENGTH))
                      .withPrincipal(
                          StringUtils.truncate(
                              StringUtils.defaultString(principal), MAX_PRINCIPAL_LENGTH))
                      .withCreatedAt(Instant.now().toEpochMilli())
                      .build());
            } catch (Exception e) {
              LOG.warn(
                  "Failed to record SCIM error history for {} status {}",
                  requestPath,
                  httpStatus,
                  e);
            }
          });
    } catch (Exception e) {
      LOG.warn("Failed to record SCIM error history for {} status {}", requestPath, httpStatus, e);
    }
  }

  /**
   * Counts error history rows for the given metalake.
   *
   * @param metalakeName target metalake name
   * @return row count, or {@code 0} when the metalake is unknown
   */
  public long countScimErrorHistory(String metalakeName) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(metalakeName), "metalakeName must not be blank");
    return ERROR_HISTORY_META_SERVICE.countScimErrorHistory(metalakeName);
  }

  /**
   * Returns whether a metalake-scoped Users/Groups failure should be persisted. HTTP 404 and
   * non-error statuses are skipped.
   *
   * @param httpStatus HTTP status
   * @param requestPath request URI
   * @return {@code true} when the failure is in scope
   */
  public static boolean shouldRecord(int httpStatus, String requestPath) {
    if (httpStatus < 400 || httpStatus == 404 || StringUtils.isBlank(requestPath)) {
      return false;
    }
    return USERS_OR_GROUPS_PATH.matcher(requestPath).matches();
  }
}
