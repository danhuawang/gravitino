/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

// We have placed this class in the server module rather than the core module because later
// authorization relies on
// org.apache.gravitino.server.authorization.MetadataFilterHelper#filterByPrivilege in the
// server-common.
package com.datastrato.gravitino.server.web.metric;

import static com.datastrato.gravitino.server.web.metric.MetricsCalculator.EMPTY_STRING_ARRAY;

import com.datastrato.gravitino.metrics.MetricsConfig;
import com.datastrato.gravitino.storage.relational.MetricPO;
import com.datastrato.gravitino.storage.relational.service.MetricDataService;
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
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCollector implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

  private final MetalakeDispatcher metalakeDispatcher;
  private final CatalogDispatcher catalogDispatcher;
  private final TagDispatcher tagDispatcher;
  private final AccessControlDispatcher accessControlDispatcher;
  private final MetricDataService metricDataService;
  private final MetricsCalculator metricsCalculator;

  // Scheduled executor for periodic metric collection
  private final ScheduledThreadPoolExecutor scheduledExecutor;
  private final int scheduledPeriodDays = 1;

  // Executor for parallel computation of metrics, create independent thread for each metalake
  private final ExecutorService metricsCalculationExecutor;
  private final int metricsCalculationCoreThreadsNum = 2;
  private final int metricsCalculationMaxThreadsNum = 5;
  private final long metricsCalculationKeepAliveTimeSec = 60L;
  private final int metricsCalculationQueueSize = 50;

  private final Set<String> serviceAdmins;
  private final Set<String> piiTags;
  private final Set<String> publicTags;
  private final Set<String> confidentialTags;
  private final Set<String> privateTags;

  private final Duration retentionPeriod;
  private final boolean enableAuthorization;

  private ZonedDateTime batchDate;

  public MetricsCollector(
      ServerConfig serverConfig,
      MetalakeDispatcher dispatcher,
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      TagDispatcher tagDispatcher,
      AccessControlDispatcher accessControlDispatcher,
      MetricDataService metricDataService) {
    this.metalakeDispatcher = dispatcher;
    this.catalogDispatcher = catalogDispatcher;
    this.tagDispatcher = tagDispatcher;
    this.accessControlDispatcher = accessControlDispatcher;
    this.metricDataService = metricDataService;
    this.metricsCalculator =
        new MetricsCalculator(
            schemaDispatcher,
            tableDispatcher,
            filesetDispatcher,
            topicDispatcher,
            modelDispatcher,
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

    this.serviceAdmins =
        serverConfig.get(Configs.SERVICE_ADMINS).stream()
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

  public void refreshMetricsForUser(String metalakeName, String userName) {
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
    calculateMetricsForUser(metalakeName, userName);
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

    if (users.length == 0) {
      LOG.warn("[batch: {}] No users found in metalake: {}", batchDate, metalakeName);
      return;
    }

    for (String userName : users) {
      LOG.info(
          "[batch: {}] Calculating metrics for user: {} in metalake: {}",
          batchDate,
          userName,
          metalakeName);
      try {
        calculateMetricsForUser(metalakeName, userName);
      } catch (Exception e) {
        LOG.error(
            "[batch: {}] Failed to calculate metrics for user: {} in metalake: {}",
            batchDate,
            userName,
            metalakeName,
            e);
      }
      LOG.info(
          "[batch: {}] Metrics for user: {} in metalake: {} processed successfully",
          batchDate,
          userName,
          metalakeName);
    }
    LOG.info("[batch: {}] Metrics for metalake {} processed successfully", batchDate, metalakeName);
  }

  private void calculateMetricsForUser(String metalake, String userName) {
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
    metrics.add(createMetricPO(MetricDataService.Metric.ASSET_WITH_TAG_COUNT, taggedAssetCount));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.ASSET_WITHOUT_TAG_COUNT, assetCount - taggedAssetCount));

    long taggedPiiAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake, principal, catalogs, piiTags.toArray(EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(MetricDataService.Metric.ASSET_WITH_PII_TAG_COUNT, taggedPiiAssetCount));

    long taggedPublicAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake, principal, catalogs, publicTags.toArray(EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.ASSET_WITH_PUBLIC_TAG_COUNT, taggedPublicAssetCount));

    long taggedConfidentialAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake, principal, catalogs, confidentialTags.toArray(EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.ASSET_WITH_CONFIDENTIAL_TAG_COUNT,
            taggedConfidentialAssetCount));

    long taggedPrivateAssetCount =
        metricsCalculator.getTaggedAssetCount(
            metalake, principal, catalogs, privateTags.toArray(EMPTY_STRING_ARRAY));
    metrics.add(
        createMetricPO(
            MetricDataService.Metric.ASSET_WITH_PRIVATE_TAG_COUNT, taggedPrivateAssetCount));

    long assetWithOwnerCount =
        (enableAuthorization && serviceAdmins.contains(userName))
            ? metricDataService.getAssetWithOwnerCount(metalake)
            : 0;
    metrics.add(
        createMetricPO(MetricDataService.Metric.ASSET_WITH_OWNER_COUNT, assetWithOwnerCount));

    persistMetrics(metalake, userName, metrics);
  }

  private Catalog[] getCatalogInfos(UserPrincipal principal, String metalakeName) {
    try {
      return PrincipalUtils.doAs(
          principal,
          () -> catalogDispatcher.listCatalogsInfo(NamespaceUtil.ofCatalog(metalakeName)));
    } catch (Exception e) {
      LOG.error(
          "Failed to list catalogs for user: {} in metalake: {}",
          principal.getName(),
          metalakeName,
          e);
      return new Catalog[0];
    }
  }

  private String[] getTagNames(UserPrincipal principal, String metalakeName) {
    try {
      return PrincipalUtils.doAs(principal, () -> tagDispatcher.listTags(metalakeName));
    } catch (Exception e) {
      LOG.error(
          "Failed to list tags for user: {} in metalake: {}", principal.getName(), metalakeName, e);
      return EMPTY_STRING_ARRAY;
    }
  }

  private void persistMetrics(String metalakeName, String user, List<MetricPO> metrics) {
    if (metrics.isEmpty()) {
      LOG.warn("No metrics to persist for metalake: {} and user: {}", metalakeName, user);
      return;
    }
    metricDataService.insertMetrics(metalakeName, user, metrics);
  }

  private MetricPO createMetricPO(MetricDataService.Metric metric, double value) {
    return MetricPO.builder().withMetricName(metric.getName()).withMetricValue(value).build();
  }
}
