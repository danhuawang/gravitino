/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

// We have placed this class in the server module rather than the core module because later
// authorization relies on
// org.apache.gravitino.server.authorization.MetadataFilterHelper#filterByPrivilege in the
// server-common.
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.security.auth.UserPrincipal;
import java.io.Closeable;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCollector implements Closeable {

  public static MetricsCollector getInstance() {
    return INSTANCE;
  }

  private static final MetricsCollector INSTANCE = new MetricsCollector();
  private static final Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

  private MetalakeDispatcher metalakeDispatcher;
  private CatalogDispatcher catalogDispatcher;
  private TagDispatcher tagDispatcher;
  private AccessControlDispatcher accessControlDispatcher;
  private MetricDataService metricDataService;
  private MetricsCalculator metricsCalculator;

  // Scheduled executor for periodic metric collection
  private ScheduledThreadPoolExecutor scheduledExecutor;
  private int scheduledPeriodDays = 1;

  // Executor for parallel computation of metrics, create independent thread for each metalake
  private ExecutorService metricsCalculationExecutor;
  private final int metricsCalculationCoreThreadsNum = 2;
  private final int metricsCalculationMaxThreadsNum = 5;
  private final long metricsCalculationKeepAliveTimeSec = 60L;
  private final int metricsCalculationQueueSize = 50;

  private final int maxCalculationRetriesForUser = 3;
  private final long calculationRetryDelayMs = 60_000;

  private Set<String> piiTags;
  private Set<String> publicTags;
  private Set<String> confidentialTags;
  private Set<String> privateTags;

  private Duration retentionPeriod;
  private boolean enableAuthorization;

  private ZonedDateTime batchDate;

  private MetricsCollector() {}

  public void initialize(ServerConfig serverConfig, GravitinoEnv gravitinoEnv) {
    this.metalakeDispatcher = gravitinoEnv.metalakeDispatcher();
    this.catalogDispatcher = gravitinoEnv.catalogDispatcher();
    this.tagDispatcher = gravitinoEnv.tagDispatcher();
    this.accessControlDispatcher = gravitinoEnv.accessControlDispatcher();
    this.metricDataService = MetricDataService.getInstance();
    metricDataService.initialize(
        gravitinoEnv.ownerDispatcher(), serverConfig.get(Configs.ENABLE_AUTHORIZATION));
    this.metricsCalculator =
        new MetricsCalculator(
            gravitinoEnv.schemaDispatcher(),
            gravitinoEnv.tableDispatcher(),
            gravitinoEnv.filesetDispatcher(),
            gravitinoEnv.topicDispatcher(),
            gravitinoEnv.modelDispatcher(),
            tagDispatcher);

    ThreadFactory namedThreadFactory =
        new ThreadFactoryBuilder()
            .setNameFormat("metrics-scheduler-thread-%d")
            .setDaemon(true)
            .build();
    this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, namedThreadFactory);
    scheduledExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

    ThreadFactory calculatorThreadFactory =
        new ThreadFactoryBuilder()
            .setNameFormat("metrics-calculator-thread-%d")
            .setDaemon(true)
            .build();
    this.metricsCalculationExecutor =
        new ThreadPoolExecutor(
            metricsCalculationCoreThreadsNum,
            metricsCalculationMaxThreadsNum,
            metricsCalculationKeepAliveTimeSec,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(metricsCalculationQueueSize),
            calculatorThreadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());

    this.piiTags =
        serverConfig.get(MetricsConfig.PII_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    this.publicTags =
        serverConfig.get(MetricsConfig.PUBLIC_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    this.confidentialTags =
        serverConfig.get(MetricsConfig.CONFIDENTIAL_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    this.privateTags =
        serverConfig.get(MetricsConfig.PRIVATE_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    this.retentionPeriod = Duration.ofDays(serverConfig.get(MetricsConfig.RETENTION_DAYS_CONFIG));

    this.enableAuthorization = serverConfig.get(Configs.ENABLE_AUTHORIZATION);
    this.batchDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault());
  }

  public void start() {
    // execute the metrics collection immediately for the first time
    scheduledExecutor.execute(this::collectAllMetrics);

    // schedule the metrics collection to run daily at midnight
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
    long initialDelay = Duration.between(now, nextMidnight).toMillis();

    scheduledExecutor.scheduleAtFixedRate(
        this::collectThenCleanMetrics,
        initialDelay,
        TimeUnit.DAYS.toMillis(scheduledPeriodDays),
        TimeUnit.MILLISECONDS);

    LOG.info("Metrics collector started");
  }

  @Override
  public void close() {
    LOG.info("Shutting down metrics collector...");
    if (scheduledExecutor != null) {
      try {
        scheduledExecutor.shutdownNow();
      } catch (Exception e) {
        LOG.error("Failed to shutdown scheduled executor", e);
      }
    }

    if (metricsCalculationExecutor != null) {
      try {
        metricsCalculationExecutor.shutdownNow();
      } catch (Exception e) {
        LOG.error("Failed to shutdown metrics calculation executor", e);
      }
    }
  }

  public void refreshMetricsForUser(String metalakeName, String userName) throws Exception {
    NameIdentifier metalakeIdent = NameIdentifierUtil.ofMetalake(metalakeName);
    // ensure the metalake exists before proceeding
    metalakeDispatcher.loadMetalake(metalakeIdent);

    if (enableAuthorization) {
      // ensure the user exists in the metalake
      accessControlDispatcher.getUser(metalakeName, userName);
    }

    if (!enableAuthorization) {
      userName = AuthConstants.ANONYMOUS_USER;
    }

    LOG.info(
        "Starting to refresh metrics data for user: {} in metalake: {}", userName, metalakeName);
    calculateMetricsForUser(
        metalakeName, userName, metricDataService.isMetalakeOwner(metalakeName, userName));
    LOG.info(
        "Metrics data refreshed successfully for user: {} in metalake: {}", userName, metalakeName);
  }

  private void collectThenCleanMetrics() {
    batchDate = batchDate.plusDays(1);
    collectAllMetrics();
    cleanExpiredMetrics();
  }

  private void collectAllMetrics() {
    try {
      LOG.info(
          "[batch: {}] Starting to collect metrics data for all users in all metalakes", batchDate);
      Metalake[] metalakes = metalakeDispatcher.listMetalakes();

      // create independent tasks for each metalake
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (Metalake metalake : metalakes) {
        String metalakeName = metalake.name();

        CompletableFuture<Void> future =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    calculateMetricsForMetalake(metalakeName);
                  } catch (Exception e) {
                    LOG.error(
                        "[batch: {}] Failed to process metrics for metalake: {}",
                        batchDate,
                        metalakeName,
                        e);
                  }
                },
                metricsCalculationExecutor);

        futures.add(future);
      }

      // wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      LOG.info(
          "[batch: {}] All metrics data collected successfully for all users in all metalakes",
          batchDate);
    } catch (Exception e) {
      LOG.error(
          "[batch: {}] Failed to collect metrics data for all users in all metalakes",
          batchDate,
          e);
    }
  }

  private void cleanExpiredMetrics() {
    long currentTimestamp = System.currentTimeMillis();
    long oldestTimestamp = currentTimestamp - retentionPeriod.toMillis();
    try {
      metricDataService.cleanMetricsByTimestamp(oldestTimestamp);
      LOG.info("Expired metrics cleaned successfully, older than: {}", new Date(oldestTimestamp));

      metricDataService.cleanInvalidMetrics();
    } catch (Exception e) {
      LOG.error("Failed to clean expired metrics", e);
    }
  }

  private void calculateMetricsForMetalake(String metalakeName) {
    LOG.info("[batch: {}] Starting to process metrics for metalake: {}", batchDate, metalakeName);
    String[] users =
        enableAuthorization
            ? Arrays.stream(accessControlDispatcher.listUsers(metalakeName))
                .map(User::name)
                .toArray(String[]::new)
            : new String[] {AuthConstants.ANONYMOUS_USER};

    if (enableAuthorization) {
      // calculate metrics for metalake owner
      try {
        String metalakeOwner = metricDataService.getMetalakeOwner(metalakeName).name();
        String actionDesc =
            String.format(
                "[batch: %s] Calculating metrics for metalake owner: %s in metalake: %s",
                batchDate, metalakeOwner, metalakeName);
        executeWithRetry(
            () -> calculateMetricsForUser(metalakeName, metalakeOwner, true), actionDesc);
      } catch (Exception e) {
        LOG.error(
            "[batch: {}] Failed to calculate metrics for metalake owner in metalake: {}",
            batchDate,
            metalakeName,
            e);
      }
    }

    if (users.length == 0) {
      LOG.warn("[batch: {}] No users found in metalake: {}", batchDate, metalakeName);
      return;
    }

    for (String userName : users) {
      String actionDesc =
          String.format(
              "[batch: %s] Calculating metrics for user: %s in metalake: %s",
              batchDate, userName, metalakeName);
      executeWithRetry(() -> calculateMetricsForUser(metalakeName, userName, false), actionDesc);
    }
    LOG.info("[batch: {}] Metrics for metalake {} processed successfully", batchDate, metalakeName);
  }

  @FunctionalInterface
  private interface ThrowableRunnable {
    void run() throws Exception;
  }

  private void executeWithRetry(ThrowableRunnable action, String actionDescription) {
    boolean success = false;
    for (int attempt = 1; attempt <= maxCalculationRetriesForUser; attempt++) {
      try {
        LOG.info("{} (Attempt {}/{})", actionDescription, attempt, maxCalculationRetriesForUser);
        action.run();
        success = true;
        LOG.info("{} processed successfully", actionDescription);
        break;
      } catch (Exception e) {
        LOG.warn(
            "{} failed on attempt {}/{}: ",
            actionDescription,
            attempt,
            maxCalculationRetriesForUser,
            e);
        if (attempt < maxCalculationRetriesForUser) {
          try {
            TimeUnit.MILLISECONDS.sleep(calculationRetryDelayMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.error("Retry delay interrupted for {}", actionDescription, ie);
            break; // Exit retry loop if interrupted
          }
        }
      }
    }
    if (!success) {
      LOG.error(
          "Failed to process {} after {} attempts. Giving up.",
          actionDescription,
          maxCalculationRetriesForUser);
    }
  }

  private void calculateMetricsForUser(String metalake, String userName, boolean forMetalakeOwner)
      throws Exception {
    UserPrincipal principal = new UserPrincipal(userName);
    Catalog[] catalogs = getCatalogInfos(principal, metalake);
    List<MetricPO> metrics = new ArrayList<>();

    long catalogCount = catalogs.length;
    metrics.add(createMetricPO(MetricDataService.Metric.CATALOG_COUNT, catalogCount));

    MetricsCalculator.AssetCounts assetCounts =
        metricsCalculator.calculateAssetCountsByCatalogs(principal, metalake, catalogs);
    long assetCount = assetCounts.getTotalAssetCount(catalogCount);
    metrics.add(createMetricPO(MetricDataService.Metric.ASSET_COUNT, assetCount));

    long schemaCount = assetCounts.getSchemaCount();
    metrics.add(createMetricPO(MetricDataService.Metric.SCHEMA_COUNT, schemaCount));

    long tableCount = assetCounts.getTableCount();
    metrics.add(createMetricPO(MetricDataService.Metric.TABLE_COUNT, tableCount));

    long filesetCount = assetCounts.getFilesetCount();
    metrics.add(createMetricPO(MetricDataService.Metric.FILESET_COUNT, filesetCount));

    long topicCount = assetCounts.getTopicCount();
    metrics.add(createMetricPO(MetricDataService.Metric.TOPIC_COUNT, topicCount));

    long modelCount = assetCounts.getModelCount();
    metrics.add(createMetricPO(MetricDataService.Metric.MODEL_COUNT, modelCount));

    String[] tagNames = getTagNames(principal, metalake);
    long tagCount = tagNames.length;
    metrics.add(createMetricPO(MetricDataService.Metric.TAG_COUNT, tagCount));

    long taggedAssetCount =
        metricsCalculator.getTaggedAssetCount(metalake, principal, catalogs, tagNames);
    metrics.add(createMetricPO(MetricDataService.Metric.TAGGED_ASSET_COUNT, taggedAssetCount));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.UNTAGGED_ASSET_COUNT, assetCount - taggedAssetCount));

    long taggedPiiAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake, principal, catalogs, piiTags.toArray(MetricsCalculator.EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(MetricDataService.Metric.PII_TAGGED_ASSET_COUNT, taggedPiiAssetCount));

    long taggedPublicAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake,
            principal,
            catalogs,
            publicTags.toArray(MetricsCalculator.EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(MetricDataService.Metric.PUBLIC_TAGGED_ASSET_COUNT, taggedPublicAssetCount));

    long taggedConfidentialAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake,
            principal,
            catalogs,
            confidentialTags.toArray(MetricsCalculator.EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.CONFIDENTIAL_TAGGED_ASSET_COUNT,
            taggedConfidentialAssetCount));

    long taggedPrivateAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake,
            principal,
            catalogs,
            privateTags.toArray(MetricsCalculator.EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.PRIVATE_TAGGED_ASSET_COUNT, taggedPrivateAssetCount));

    if (forMetalakeOwner) {
      long assetWithOwnerCount = metricDataService.getAssetWithOwnerCount(metalake);
      metrics.add(createMetricPO(MetricDataService.Metric.OWNED_ASSET_COUNT, assetWithOwnerCount));
    }

    persistMetrics(metalake, userName, metrics, forMetalakeOwner);
  }

  private Catalog[] getCatalogInfos(UserPrincipal principal, String metalakeName) throws Exception {
    return PrincipalUtils.doAs(
        principal, () -> catalogDispatcher.listCatalogsInfo(NamespaceUtil.ofCatalog(metalakeName)));
  }

  private String[] getTagNames(UserPrincipal principal, String metalakeName) throws Exception {
    return PrincipalUtils.doAs(principal, () -> tagDispatcher.listTags(metalakeName));
  }

  private void persistMetrics(
      String metalakeName, String user, List<MetricPO> metrics, boolean forMetalakeOwner) {
    if (metrics.isEmpty()) {
      LOG.warn("No metrics to persist for metalake: {} and user: {}", metalakeName, user);
      return;
    }
    metricDataService.insertMetrics(metalakeName, user, metrics, forMetalakeOwner);
  }

  private MetricPO createMetricPO(MetricDataService.Metric metric, double value) {
    return MetricPO.builder().withMetricName(metric.getName()).withMetricValue(value).build();
  }
}
