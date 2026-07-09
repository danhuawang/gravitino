/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import com.datastrato.gravitino.license.mapper.LicenseNodeMapper;
import com.google.common.annotations.VisibleForTesting;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LicenseManager {

  private static final Logger LOG = LoggerFactory.getLogger(LicenseManager.class);

  // Distinct from the generic exit code 1 so k8s/chart tooling (e.g. a readiness probe or
  // entrypoint wrapper) can recognize a license failure specifically and avoid blindly
  // restarting the pod for a condition that a restart cannot fix.
  @VisibleForTesting static final int LICENSE_VIOLATION_EXIT_CODE = 78;

  private static final class InstanceHolder {
    private static final LicenseManager INSTANCE = new LicenseManager();
  }

  public enum StatusCode {
    VALID,
    EXPIRING_SOON,
    IN_GRACE_PERIOD,
    EXPIRED
  }

  @Getter
  @RequiredArgsConstructor
  public static class LicenseStatus {
    private final StatusCode statusCode;
    private final String issuedTo;
    private final String licenseId;
    private final long expiresAtDays;
    /**
     * Days remaining until the license or grace period ends. Negative when {@link
     * StatusCode#EXPIRED}: the absolute value is how many days past the grace period end.
     */
    private final long daysRemaining;

    private final int gracePeriodDays;
    private final int maxNodes;
    @Getter @Setter private volatile int activeNodes;
  }

  private volatile LicenseStatus currentStatus;
  private volatile LicensePayload payload;

  @VisibleForTesting String nodeId;
  private LicenseConfig licenseConfig;

  private ScheduledExecutorService expiryScheduler;
  private ScheduledExecutorService heartbeatScheduler;

  private LicenseVerifier verifier;

  @VisibleForTesting Clock clock = Clock.systemUTC();
  @VisibleForTesting Runnable exitHandler = () -> System.exit(LICENSE_VIOLATION_EXIT_CODE);

  // Timestamp of the first heartbeat cycle that detected rank > maxNodes. Null when the node is
  // within the limit. Used to implement a grace period equal to nodeStaleMinutes: a transient
  // over-count caused by a kill-9 ghost row or a rolling-upgrade overlap resolves on its own
  // within that window, so we only evict if the violation persists beyond it.
  @VisibleForTesting volatile Instant overLimitSince = null;

  public static LicenseManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  LicenseManager() {
    // Package private constructor to prevent external instantiation
  }

  /** Full initialization: verify key, register node, start background thread. */
  public void initialize(Config config) {
    Map<String, String> props = config.getAllConfig();
    this.licenseConfig = new LicenseConfig(props);
    if (licenseConfig.getNodeStaleMinutes() <= licenseConfig.getNodeHeartbeatIntervalMinutes()) {
      throw new IllegalArgumentException(
          "gravitino.datastrato.license.nodeStaleMinutes must be greater than "
              + "gravitino.datastrato.license.nodeHeartbeatIntervalMinutes");
    }
    this.nodeId = UUID.randomUUID().toString();
    // Env var takes precedence over config file
    String key = System.getenv(LicenseConfig.LICENSE_KEY_ENV);
    if (StringUtils.isBlank(key)) {
      key = licenseConfig.getLicenseKey();
    }
    verifyAndCache(key);

    try {
      registerNode();
      startPeriodicCheck();
    } catch (RuntimeException e) {
      shutdown();
      throw e;
    }

    logStartupStatus();
  }

  /** Test-only: verify and cache without DB or scheduler. */
  void initForTest(String key, Map<String, String> props) {
    this.licenseConfig = new LicenseConfig(props);
    if (licenseConfig.getNodeStaleMinutes() <= licenseConfig.getNodeHeartbeatIntervalMinutes()) {
      throw new IllegalArgumentException(
          "gravitino.datastrato.license.nodeStaleMinutes must be greater than "
              + "gravitino.datastrato.license.nodeHeartbeatIntervalMinutes");
    }
    verifyAndCache(key);
  }

  /** Test-only: inject an in-memory verifier instead of loading from classpath. */
  void setVerifierForTest(LicenseVerifier verifier) {
    this.verifier = verifier;
  }

  // ── public ────────────────────────────────────────────────────────────────

  public void shutdown() {
    if (expiryScheduler != null) {
      expiryScheduler.shutdownNow();
      awaitScheduler(expiryScheduler, "license-expiry-checker");
    }
    if (heartbeatScheduler != null) {
      heartbeatScheduler.shutdownNow();
      awaitScheduler(heartbeatScheduler, "license-heartbeat");
    }
    if (nodeId != null) {
      SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(nodeId));
    }
  }

  public LicenseStatus getStatus() {
    return currentStatus;
  }

  // ── private ───────────────────────────────────────────────────────────────

  private void awaitScheduler(ScheduledExecutorService scheduler, String name) {
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        LOG.warn("Scheduler '{}' did not terminate within 5 seconds", name);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while waiting for scheduler '{}' to terminate", name);
    }
  }

  private LicenseVerifier getVerifier() {
    return verifier != null ? verifier : LicenseVerifier.fromClasspath();
  }

  private void verifyAndCache(String key) {
    if (StringUtils.isBlank(key)) {
      throw new LicenseException(
          LicenseException.ErrorCode.MISSING,
          "No license key found. Set 'gravitino.datastrato.license.key' in gravitino.conf"
              + " or the GRAVITINO_LICENSE_KEY environment variable.");
    }
    this.payload = getVerifier().verify(key);
    this.currentStatus = computeStatus();
    if (currentStatus.getStatusCode() == StatusCode.EXPIRED) {
      throw new LicenseException(
          LicenseException.ErrorCode.EXPIRED,
          "past grace period by " + Math.abs(currentStatus.getDaysRemaining()) + " days");
    }
  }

  private LicenseStatus computeStatus() {
    long todayDays = LocalDate.now(clock).toEpochDay();
    long expiresAtDays = payload.getExpiresAtDays();
    long graceEndsAtDays = expiresAtDays + payload.getGracePeriodDays();
    int warnThreshold = licenseConfig != null ? licenseConfig.getWarnDaysBeforeExpiry() : 30;

    StatusCode code;
    long daysRemaining;
    if (todayDays > graceEndsAtDays) {
      code = StatusCode.EXPIRED;
      daysRemaining = graceEndsAtDays - todayDays;
    } else if (todayDays > expiresAtDays) {
      code = StatusCode.IN_GRACE_PERIOD;
      daysRemaining = graceEndsAtDays - todayDays;
    } else {
      daysRemaining = expiresAtDays - todayDays;
      code = daysRemaining <= warnThreshold ? StatusCode.EXPIRING_SOON : StatusCode.VALID;
    }

    LicenseStatus status =
        new LicenseStatus(
            code,
            payload.getIssuedTo(),
            payload.getLicenseId().toString(),
            expiresAtDays,
            daysRemaining,
            payload.getGracePeriodDays(),
            payload.getMaxNodes());
    if (currentStatus != null) {
      status.setActiveNodes(currentStatus.getActiveNodes());
    }
    return status;
  }

  private void registerNode() {
    SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.upsertNode(nodeId));
  }

  private void startPeriodicCheck() {
    int expiryIntervalHours = licenseConfig.getCheckIntervalHours();
    expiryScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "license-expiry-checker");
              t.setDaemon(true);
              return t;
            });
    expiryScheduler.scheduleAtFixedRate(
        this::periodicCheck, expiryIntervalHours, expiryIntervalHours, TimeUnit.HOURS);

    int heartbeatIntervalMinutes = licenseConfig.getNodeHeartbeatIntervalMinutes();
    heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "license-heartbeat");
              t.setDaemon(true);
              return t;
            });
    // Use a short initial delay so activeNodes is populated quickly for the REST status endpoint.
    heartbeatScheduler.scheduleAtFixedRate(
        this::heartbeatCheck, 1, heartbeatIntervalMinutes, TimeUnit.MINUTES);
  }

  private void periodicCheck() {
    this.currentStatus = computeStatus();
    String contactUrl = licenseConfig.getRenewalContactUrl();
    switch (currentStatus.getStatusCode()) {
      case EXPIRING_SOON:
        LOG.warn(
            "License expires in {} days ({}). Please contact Datastrato to renew: {}",
            currentStatus.getDaysRemaining(),
            LocalDate.ofEpochDay(payload.getExpiresAtDays()),
            contactUrl);
        break;
      case IN_GRACE_PERIOD:
        LOG.warn(
            "License expired. Gravitino will stop in {} days. Contact Datastrato: {}",
            currentStatus.getDaysRemaining(),
            contactUrl);
        break;
      case EXPIRED:
        LOG.error("License grace period exhausted. Gravitino will shut down.");
        exitHandler.run();
        break;
      default:
        break;
    }
  }

  @VisibleForTesting
  void heartbeatCheck() {
    int attempts = 0;
    while (true) {
      try {
        doHeartbeatCheck();
        return;
      } catch (Exception e) {
        if (isDeadlock(e) && attempts++ < 2) {
          long jitterMs = ThreadLocalRandom.current().nextLong(50, 201);
          LOG.debug(
              "License heartbeat hit a database deadlock; retrying in {} ms (attempt {})",
              jitterMs,
              attempts);
          try {
            Thread.sleep(jitterMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("License heartbeat interrupted during deadlock backoff", ie);
            return;
          }
          continue;
        }
        LOG.warn("License heartbeat check failed — will retry next interval", e);
        return;
      }
    }
  }

  /**
   * Returns true if the exception chain contains a deadlock signal. Checks ANSI SQLState 40001
   * (MySQL, H2) and PostgreSQL-specific 40P01.
   */
  @VisibleForTesting
  static boolean isDeadlock(Throwable t) {
    for (Throwable cause = t; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException) {
        String state = ((SQLException) cause).getSQLState();
        if ("40001".equals(state) || "40P01".equals(state)) {
          return true;
        }
      }
    }
    return false;
  }

  @VisibleForTesting
  void doHeartbeatCheck() {
    long staleIntervalMs = TimeUnit.MINUTES.toMillis(licenseConfig.getNodeStaleMinutes());

    boolean unlimited = payload.getMaxNodes() == -1;
    int[] rankHolder = {0};
    int[] totalHolder = {0};
    SessionUtils.doMultipleWithCommit(
        // Upsert this node first so it is never self-purged by the stale cleanup below.
        () -> SessionUtils.doWithoutCommit(LicenseNodeMapper.class, m -> m.upsertNode(nodeId)),
        () ->
            SessionUtils.doWithoutCommit(
                LicenseNodeMapper.class, m -> m.deleteStaleNodes(staleIntervalMs)),
        () -> {
          totalHolder[0] =
              SessionUtils.getWithoutCommit(
                  LicenseNodeMapper.class, LicenseNodeMapper::countActiveNodes);
          if (!unlimited) {
            rankHolder[0] =
                SessionUtils.getWithoutCommit(LicenseNodeMapper.class, m -> m.rankNode(nodeId));
          }
        });

    currentStatus.setActiveNodes(totalHolder[0]);
    if (!unlimited) {
      if (rankHolder[0] == 0) {
        // Node was concurrently removed between upsert and rankNode — treat as "unknown" and skip
        // this cycle. We intentionally leave overLimitSince unchanged: if a prior violation was
        // genuine we must not reset the grace-period clock on an inconclusive result; if no
        // violation was recorded yet, overLimitSince is still null and nothing changes.
        LOG.warn(
            "rankNode returned 0 for nodeId={} — node may have been concurrently removed; "
                + "skipping enforcement this cycle",
            nodeId);
      } else if (rankHolder[0] > payload.getMaxNodes()) {
        if (overLimitSince == null) {
          // First cycle over the limit: start the grace period rather than evicting immediately.
          // A kill-9 ghost row or a rolling-upgrade overlap will resolve within nodeStaleMinutes,
          // so deferring eviction avoids spurious self-shutdown in those transient cases.
          this.overLimitSince = clock.instant();
          LOG.warn(
              "Node limit exceeded: rank={} max={}. Will shut down if unresolved after approximately {} minutes.",
              rankHolder[0],
              payload.getMaxNodes(),
              licenseConfig.getNodeStaleMinutes());
        } else {
          // Use the same staleIntervalMs as deleteStaleNodes so both thresholds share a single
          // config value in the same unit (ms). Use strict > so the eviction fires only after
          // the ghost is guaranteed stale: overLimitSince is set strictly after the ghost's last
          // heartbeat, so overLimitSince + staleIntervalMs > ghost_heartbeat + staleIntervalMs —
          // the ghost is always cleaned up by deleteStaleNodes before rankNode is re-evaluated.
          long elapsedMs = Duration.between(overLimitSince, clock.instant()).toMillis();
          if (elapsedMs > staleIntervalMs) {
            LOG.error("Node limit exceeded and unresolved for {} ms — shutting down.", elapsedMs);
            SessionUtils.doWithCommit(LicenseNodeMapper.class, m -> m.deleteNode(nodeId));
            // In production exitHandler calls System.exit(LICENSE_VIOLATION_EXIT_CODE) and never
            // returns.
            // Reset the field here only to avoid repeated deleteNode calls in test environments
            // where the exit
            // handler is mocked and returns normally.
            this.overLimitSince = null;
            exitHandler.run();
          }
        }
      } else {
        // Violation resolved (ghost expired or old node stopped): reset so the next violation
        // starts a fresh grace period rather than accumulating toward the previous deadline.
        if (overLimitSince != null) {
          LOG.info(
              "Node limit violation resolved: rank={} max={}. Grace period cleared.",
              rankHolder[0],
              payload.getMaxNodes());
        }
        this.overLimitSince = null;
      }
    }
  }

  private void logStartupStatus() {
    LicenseStatus s = currentStatus;
    String expDate = LocalDate.ofEpochDay(payload.getExpiresAtDays()).toString();
    String nodesStr = payload.getMaxNodes() == -1 ? "unlimited" : "max " + payload.getMaxNodes();
    String contactUrl = licenseConfig.getRenewalContactUrl();
    switch (s.getStatusCode()) {
      case VALID:
        LOG.info(
            "License valid. Issued to: {}. Expires: {} ({} days remaining). Nodes: {}.",
            s.getIssuedTo(),
            expDate,
            s.getDaysRemaining(),
            nodesStr);
        break;
      case EXPIRING_SOON:
        LOG.warn(
            "License expires in {} days ({}). Please contact Datastrato to renew: {}",
            s.getDaysRemaining(),
            expDate,
            contactUrl);
        break;
      case IN_GRACE_PERIOD:
        LOG.warn(
            "License expired. Gravitino will stop in {} days. Contact Datastrato: {}",
            s.getDaysRemaining(),
            contactUrl);
        break;
      default:
        break;
    }
  }
}
