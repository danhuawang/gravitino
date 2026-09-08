/*
 * Copyright 2024 Datastrato Inc.
 */

// We have placed this class in the server module rather than the core module because later
// authorization relies on
// org.apache.gravitino.server.authorization.MetadataFilterHelper#filterByPrivilege in the
// server-common.
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.OwnerNameRelPO;
import com.datastrato.gravitino.metrics.storage.relational.TagNameMetadataObjectRelPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.rel.ViewCatalog;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCollector implements Closeable {
  public static final Long MOCK_USER_ID_FOR_DISABLE_AUTHZ = 0L;
  public static final Long MOCK_USER_ID_FOR_METALAKE_OWNER = 1L;
  private static final MetricsCollector INSTANCE = new MetricsCollector();
  private static final Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

  private CatalogManager catalogManager;
  private SchemaDispatcher schemaDispatcher;
  private TableDispatcher tableDispatcher;
  private TopicDispatcher topicDispatcher;
  private ViewDispatcher viewDispatcher;
  private MetricDataService metricDataService;
  private EntityStore store;
  private MetricsSystem metricsSystem;
  private DashboardMetricsCollectorMetricsSource metricsSource;

  // Scheduled executor for periodic metric collection
  private ScheduledThreadPoolExecutor scheduledExecutor;
  private int scheduledPeriodDays = 1;

  // Executor for parallel computation of metrics, create independent thread for each metalake
  private ExecutorService metricsCalculationExecutor;
  private final int metricsCalculationCoreThreadsNum = 2;
  private final int metricsCalculationMaxThreadsNum = 5;
  private final long metricsCalculationKeepAliveTimeSec = 60L;
  private final int metricsCalculationQueueSize = 50;

  private Duration retentionPeriod;
  private boolean enableAuthorization;

  @Getter private final Map<String, MetalakeSnapshot> metalakeSnapshots = new ConcurrentHashMap<>();

  private final Map<Long, Object> metalakeLocks = new ConcurrentHashMap<>();

  private MetricsCollector() {}

  public static MetricsCollector getInstance() {
    return INSTANCE;
  }

  public void initialize(ServerConfig serverConfig, GravitinoEnv gravitinoEnv) {
    this.catalogManager = gravitinoEnv.catalogManager();
    this.schemaDispatcher = gravitinoEnv.schemaDispatcher();
    this.tableDispatcher = gravitinoEnv.tableDispatcher();
    this.topicDispatcher = gravitinoEnv.topicDispatcher();
    this.viewDispatcher = gravitinoEnv.viewDispatcher();
    this.metricDataService = MetricDataService.getInstance();
    metricDataService.initialize(serverConfig.get(Configs.ENABLE_AUTHORIZATION));
    this.store = gravitinoEnv.entityStore();
    this.metricsSystem = gravitinoEnv.metricsSystem();
    if (metricsSystem != null) {
      metricsSource = new DashboardMetricsCollectorMetricsSource();
      metricsSystem.register(metricsSource);
    }

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

    this.retentionPeriod = Duration.ofDays(serverConfig.get(MetricsConfig.RETENTION_DAYS_CONFIG));

    this.enableAuthorization = serverConfig.get(Configs.ENABLE_AUTHORIZATION);
  }

  public void start() {
    // execute the metrics collection immediately for the first time
    scheduledExecutor.execute(
        () -> collectAllMetrics(PublishMode.CURRENT_ONLY, System.currentTimeMillis(), true));

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
        scheduledExecutor.shutdown();
        if (!scheduledExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
          LOG.warn("Metrics scheduler did not terminate within 30 seconds");
          scheduledExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduledExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        LOG.error("Failed to shutdown scheduled executor", e);
      }
    }

    if (metricsCalculationExecutor != null) {
      try {
        metricsCalculationExecutor.shutdown();
        if (!metricsCalculationExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
          metricsCalculationExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        metricsCalculationExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        LOG.error("Failed to shutdown metrics calculation executor", e);
      }
    }

    if (metricsSystem != null && metricsSource != null) {
      metricsSystem.unregister(metricsSource);
      metricsSource = null;
    }
  }

  /** Controls whether a collection updates only current data or current and history together. */
  public enum PublishMode {
    /** Replaces the current snapshot without adding a historical point. */
    CURRENT_ONLY,

    /** Replaces current and appends history in one metalake transaction. */
    CURRENT_AND_HISTORY
  }

  /** Summarizes whether every metric produced by one collection is complete. */
  public enum CollectionOutcome {
    /** Every published metric was computed from all dependencies. */
    COMPLETE,

    /** At least one published metric is partial or unavailable. */
    INCOMPLETE
  }

  private void collectAllMetrics(PublishMode publishMode, long runTimestamp) {
    collectAllMetrics(publishMode, runTimestamp, false);
  }

  private void collectAllMetrics(
      PublishMode publishMode, long runTimestamp, boolean initializeDirtyMarkers) {
    try {
      LOG.info(
          "[run: {}] Starting to collect metrics data for all users in all metalakes",
          Instant.ofEpochMilli(runTimestamp));
      List<BaseMetalake> metalakes =
          store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE).stream()
              .filter(MetricsCollector::isMetalakeInUse)
              .collect(Collectors.toList());
      Map<Long, Long> startupDirtyRevisions =
          initializeDirtyMarkers
              ? prepareStartupDirtyRevisions(metalakes, runTimestamp)
              : Collections.emptyMap();

      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (BaseMetalake metalake : metalakes) {
        String metalakeName = metalake.name();
        CompletableFuture<Void> future =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    Long startupRevision = startupDirtyRevisions.get(metalake.id());
                    CollectionOutcome outcome =
                        collectAndPublish(
                            metalake, publishMode, runTimestamp, startupRevision == null);
                    if (startupRevision != null && outcome == CollectionOutcome.COMPLETE) {
                      metricDataService.deleteDirtyIfRevision(metalake.id(), startupRevision);
                    }
                  } catch (Exception e) {
                    LOG.error(
                        "[run: {}] Failed to process metrics for metalake: {}",
                        Instant.ofEpochMilli(runTimestamp),
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
          "[run: {}] Finished collecting metrics data for all users in all metalakes",
          Instant.ofEpochMilli(runTimestamp));
    } catch (Exception e) {
      LOG.error(
          "[run: {}] Failed to list metalakes for dashboard metric collection",
          Instant.ofEpochMilli(runTimestamp),
          e);
    }
  }

  @VisibleForTesting
  void collectStartupMetrics(long runTimestamp) {
    collectAllMetrics(PublishMode.CURRENT_ONLY, runTimestamp, true);
  }

  private Map<Long, Long> prepareStartupDirtyRevisions(
      List<BaseMetalake> metalakes, long eventTime) {
    Map<Long, Long> dirtyRevisions = new HashMap<>();
    for (BaseMetalake metalake : metalakes) {
      try {
        MetricDirtyPO dirty = metricDataService.getDirtyMetalake(metalake.id());
        if (dirty == null) {
          metricDataService.markMetalakeDirty(metalake.id(), eventTime);
          dirty = metricDataService.getDirtyMetalake(metalake.id());
        }
        if (dirty == null) {
          LOG.warn(
              "Dashboard metric dirty marker was not visible after initialization for metalake {}",
              metalake.name());
          continue;
        }
        dirtyRevisions.put(metalake.id(), dirty.getRevision());
      } catch (Exception e) {
        LOG.warn(
            "Failed to initialize dashboard metric dirty marker for metalake {}",
            metalake.name(),
            e);
      }
    }
    return dirtyRevisions;
  }

  private void cleanExpiredMetrics() {
    long currentTimestamp = System.currentTimeMillis();
    long oldestTimestamp = currentTimestamp - retentionPeriod.toMillis();
    try {
      metricDataService.cleanMetricsByTimestamp(oldestTimestamp);
      LOG.info(
          "Expired metrics cleaned successfully, older than: {}",
          Instant.ofEpochMilli(oldestTimestamp));

      metricDataService.cleanInvalidMetrics();
    } catch (Exception e) {
      LOG.error("Failed to clean expired metrics", e);
    }
  }

  CollectionOutcome collectAndPublish(
      BaseMetalake metalake, PublishMode publishMode, long runTimestamp) throws Exception {
    return collectAndPublish(metalake, publishMode, runTimestamp, true);
  }

  CollectionOutcome refreshAndPublishDirtyMetalake(
      BaseMetalake metalake, PublishMode publishMode, long runTimestamp) throws Exception {
    return collectAndPublish(metalake, publishMode, runTimestamp, false);
  }

  private CollectionOutcome collectAndPublish(
      BaseMetalake metalake,
      PublishMode publishMode,
      long runTimestamp,
      boolean markDirtyOnIncomplete)
      throws Exception {
    synchronized (metalakeLock(metalake.id())) {
      String metalakeName = metalake.name();
      MetalakeSnapshot previousSnapshot = metalakeSnapshots.get(metalakeName);
      long totalStartedAt = System.nanoTime();
      try {
        long loadStartedAt = System.nanoTime();
        MetalakeSnapshot snapshot;
        try {
          snapshot = loadAllDataForMetalake(metalake);
        } finally {
          recordLoadDuration(System.nanoTime() - loadStartedAt);
        }
        metalakeSnapshots.put(metalakeName, snapshot);
        long calculationStartedAt = System.nanoTime();
        Map<Long, List<MetricPO>> metricsByUser;
        try {
          metricsByUser = calculateMetrics(snapshot);
        } finally {
          recordCalculationDuration(System.nanoTime() - calculationStartedAt);
        }
        CollectionOutcome outcome = collectionOutcome(metricsByUser);
        long publishStartedAt = System.nanoTime();
        try {
          if (publishMode == PublishMode.CURRENT_AND_HISTORY) {
            if (outcome == CollectionOutcome.INCOMPLETE && markDirtyOnIncomplete) {
              metricDataService.replaceCurrentAndAppendHistoryAndMarkDirty(
                  metalake.id(), metricsByUser, runTimestamp, runTimestamp);
            } else {
              metricDataService.replaceCurrentAndAppendHistory(
                  metalake.id(), metricsByUser, runTimestamp);
            }
          } else {
            if (outcome == CollectionOutcome.INCOMPLETE && markDirtyOnIncomplete) {
              metricDataService.replaceCurrentMetricsAndMarkDirty(
                  metalake.id(), metricsByUser, runTimestamp, runTimestamp);
            } else {
              metricDataService.replaceCurrentMetrics(metalake.id(), metricsByUser, runTimestamp);
            }
          }
        } finally {
          recordPublishDuration(System.nanoTime() - publishStartedAt);
        }
        metalakeSnapshots
            .entrySet()
            .removeIf(
                entry ->
                    !entry.getKey().equals(metalakeName)
                        && entry.getValue().getAssetTreeRoot().getId() == metalake.id());
        long publishedRows = metricsByUser.values().stream().mapToLong(List::size).sum();
        long directChildRows =
            metricsByUser.values().stream()
                .flatMap(List::stream)
                .filter(
                    metric ->
                        DirectChildCountMetricNames.isDirectChildCountMetric(
                            metric.getMetricName()))
                .count();
        if (metricsSource != null) {
          metricsSource.recordPublishedRows(publishedRows, directChildRows);
          metricsSource.recordOutcome(outcome);
        }
        LOG.info(
            "[run: {}] Dashboard metrics processed: metalake={}, outcome={}, users={}, schemas={}, "
                + "failedCatalogs={}, publishedRows={}, directChildRows={}, totalMs={}",
            Instant.ofEpochMilli(runTimestamp),
            metalakeName,
            outcome,
            metricsByUser.size(),
            snapshot.getSchemaNodes().size(),
            snapshot.getFailedCatalogNames().size(),
            publishedRows,
            directChildRows,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartedAt));
        return outcome;
      } catch (Exception e) {
        if (metricsSource != null) {
          metricsSource.recordFailure();
        }
        if (previousSnapshot == null) {
          metalakeSnapshots.remove(metalakeName);
        } else {
          metalakeSnapshots.put(metalakeName, previousSnapshot);
        }
        throw e;
      } finally {
        if (metricsSource != null) {
          metricsSource.recordTotalDuration(System.nanoTime() - totalStartedAt);
        }
      }
    }
  }

  private void recordLoadDuration(long durationNanos) {
    if (metricsSource != null) {
      metricsSource.recordLoadDuration(durationNanos);
    }
  }

  private void recordCalculationDuration(long durationNanos) {
    if (metricsSource != null) {
      metricsSource.recordCalculationDuration(durationNanos);
    }
  }

  private void recordPublishDuration(long durationNanos) {
    if (metricsSource != null) {
      metricsSource.recordPublishDuration(durationNanos);
    }
  }

  @VisibleForTesting
  static CollectionOutcome collectionOutcome(Map<Long, List<MetricPO>> metricsByUser) {
    boolean complete =
        metricsByUser.values().stream()
            .flatMap(List::stream)
            .allMatch(metric -> metric.getMetricState() == MetricState.COMPLETE);
    return complete ? CollectionOutcome.COMPLETE : CollectionOutcome.INCOMPLETE;
  }

  CompletableFuture<Void> submitIncremental(Runnable task) {
    return CompletableFuture.runAsync(task, metricsCalculationExecutor);
  }

  Object metalakeLock(long metalakeId) {
    return metalakeLocks.computeIfAbsent(metalakeId, ignored -> new Object());
  }

  Optional<BaseMetalake> findActiveMetalake(long metalakeId) throws IOException {
    return store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE).stream()
        .filter(MetricsCollector::isMetalakeInUse)
        .filter(metalake -> metalake.id() == metalakeId)
        .findFirst();
  }

  private Map<Long, List<MetricPO>> calculateMetrics(MetalakeSnapshot snapshot) {
    MetricsCalculator calculator = new MetricsCalculator(snapshot);
    Map<Long, List<MetricPO>> metricsByUser = new HashMap<>();
    if (!enableAuthorization) {
      metricsByUser.put(
          MOCK_USER_ID_FOR_DISABLE_AUTHZ, calculator.calculateMetricsForDisableAuthz());
      return metricsByUser;
    }

    GravitinoAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    try {
      authorizer.initialize();
      snapshot
          .getUserNameToUserId()
          .forEach(
              (userName, userId) ->
                  metricsByUser.put(
                      userId, calculator.calculateMetricsForUser(userName, authorizer)));
      Optional<Owner> owner =
          snapshot.getAssetTreeRoot().getOwners().stream()
              .filter(candidate -> candidate.type().equals(Owner.Type.USER))
              .filter(candidate -> snapshot.getUserNameToUserId().containsKey(candidate.name()))
              .findFirst();
      owner.ifPresent(
          value ->
              metricsByUser.put(
                  MOCK_USER_ID_FOR_METALAKE_OWNER,
                  calculator.calculateMetricsForUser(value.name(), authorizer)));
      return metricsByUser;
    } finally {
      try {
        authorizer.close();
      } catch (IOException e) {
        LOG.warn("Error closing authorizer", e);
      }
    }
  }

  /**
   * Loads all necessary data for a given metalake from the underlying storage and builds an
   * in-memory representation.
   */
  @VisibleForTesting
  MetalakeSnapshot loadAllDataForMetalake(BaseMetalake metalake) throws Exception {
    Set<AssetNode> catalogNodes = new HashSet<>();
    Set<AssetNode> schemaNodes = new HashSet<>();
    Set<AssetNode> tableNodes = new HashSet<>();
    Set<AssetNode> viewNodes = new HashSet<>();
    Set<AssetNode> functionNodes = new HashSet<>();
    Set<AssetNode> filesetNodes = new HashSet<>();
    Set<AssetNode> topicNodes = new HashSet<>();
    Set<AssetNode> modelNodes = new HashSet<>();
    Set<String> failedCatalogNames = new HashSet<>();
    Map<String, Catalog.Type> catalogTypes = new HashMap<>();
    Map<String, String> catalogProviders = new HashMap<>();
    Map<String, Boolean> viewListingSupportByCatalog = new HashMap<>();
    Map<Long, AssetNode> assetNodeById = new HashMap<>();

    // Root is the metalake
    Map<Long, Set<Owner>> objectIdToOwners = getObjectIdToOwners(metalake.id());

    AssetNode root = getMetalakeNode(metalake, objectIdToOwners);
    assetNodeById.put(root.getId(), root);

    List<CatalogEntity> catalogs =
        store.list(
            NamespaceUtil.ofCatalog(metalake.name()),
            CatalogEntity.class,
            Entity.EntityType.CATALOG);
    for (CatalogEntity catalog : catalogs) {
      if (!isCatalogInUse(catalog)) {
        LOG.info(
            "Skipping disabled catalog {} while collecting metrics for metalake {}",
            catalog.name(),
            metalake.name());
        continue;
      }
      AssetNode catalogNode = getCatalogNode(catalog, root, objectIdToOwners);

      root.addChild(catalogNode);
      assetNodeById.put(catalogNode.getId(), catalogNode);
      catalogNodes.add(catalogNode);
      catalogTypes.put(catalog.name(), catalog.getType());
      catalogProviders.put(catalog.name(), catalog.getProvider());

      Map<Long, AssetNode> catalogAssetNodeById = new HashMap<>();
      Set<AssetNode> catalogSchemaNodes = new HashSet<>();
      Set<AssetNode> catalogTableNodes = new HashSet<>();
      Set<AssetNode> catalogViewNodes = new HashSet<>();
      Set<AssetNode> catalogFunctionNodes = new HashSet<>();
      Set<AssetNode> catalogFilesetNodes = new HashSet<>();
      Set<AssetNode> catalogTopicNodes = new HashSet<>();
      Set<AssetNode> catalogModelNodes = new HashSet<>();
      try {
        CatalogCapabilities catalogCapabilities = loadCatalogCapabilities(catalog);
        boolean managedSchema = catalogCapabilities.managedSchema;
        boolean managedTable = catalogCapabilities.managedTable;
        boolean managedView = catalogCapabilities.managedView;
        boolean managedTopic = catalogCapabilities.managedTopic;
        boolean hierarchicalSchema = catalogCapabilities.hierarchicalSchema;
        boolean viewListingSupported = catalogCapabilities.viewListingSupported;

        if (catalog.getType() == Catalog.Type.RELATIONAL) {
          viewListingSupportByCatalog.put(catalog.name(), viewListingSupported);
          if (!viewListingSupported) {
            LOG.debug("Catalog {} does not support view listing; skipping views", catalog.name());
          }
        }

        Namespace nsOfSchema = NamespaceUtil.ofSchema(metalake.name(), catalog.name());
        Set<AssetNode> schemas =
            getSchemaNodes(
                nsOfSchema,
                catalogNode,
                managedSchema,
                hierarchicalSchema,
                objectIdToOwners,
                catalogAssetNodeById);
        catalogSchemaNodes.addAll(schemas);

        for (AssetNode schemaNode : schemas) {
          Namespace ns = Namespace.of(metalake.name(), catalogNode.getName(), schemaNode.getName());
          switch (catalog.getType()) {
            case RELATIONAL:
              Set<AssetNode> tables =
                  getTableNodes(
                      ns, schemaNode, managedTable, objectIdToOwners, catalogAssetNodeById);
              Set<AssetNode> views =
                  viewListingSupported
                      ? getViewNodes(
                          ns, schemaNode, managedView, objectIdToOwners, catalogAssetNodeById)
                      : Collections.emptySet();
              schemaNode.addChildren(tables);
              schemaNode.addChildren(views);
              catalogTableNodes.addAll(tables);
              catalogViewNodes.addAll(views);
              break;

            case FILESET:
              Set<AssetNode> filesets =
                  getFilesetNodes(ns, schemaNode, objectIdToOwners, catalogAssetNodeById);
              schemaNode.addChildren(filesets);
              catalogFilesetNodes.addAll(filesets);
              break;

            case MESSAGING:
              Set<AssetNode> topics =
                  getTopicNodes(
                      ns, schemaNode, managedTopic, objectIdToOwners, catalogAssetNodeById);
              schemaNode.addChildren(topics);
              catalogTopicNodes.addAll(topics);
              break;

            case MODEL:
              Set<AssetNode> models =
                  getModelNodes(ns, schemaNode, objectIdToOwners, catalogAssetNodeById);
              schemaNode.addChildren(models);
              catalogModelNodes.addAll(models);
              break;

            default:
              LOG.warn("Unsupported catalog type: {}", catalog.getType());
          }

          if (catalog.getType() != Catalog.Type.UNSUPPORTED) {
            Set<AssetNode> functions =
                getFunctionNodes(ns, schemaNode, objectIdToOwners, catalogAssetNodeById);
            schemaNode.addChildren(functions);
            catalogFunctionNodes.addAll(functions);
          }
        }

        assetNodeById.putAll(catalogAssetNodeById);
        schemaNodes.addAll(catalogSchemaNodes);
        tableNodes.addAll(catalogTableNodes);
        viewNodes.addAll(catalogViewNodes);
        functionNodes.addAll(catalogFunctionNodes);
        filesetNodes.addAll(catalogFilesetNodes);
        topicNodes.addAll(catalogTopicNodes);
        modelNodes.addAll(catalogModelNodes);
      } catch (Exception e) {
        failedCatalogNames.add(catalog.name());
        LOG.warn(
            "Catalog {} could not be collected; dashboard metrics will publish a safe incomplete result",
            catalog.name(),
            e);
      }
    }

    // Fetch authorization relative data
    List<UserEntity> userEntities =
        store.list(NamespaceUtil.ofUser(metalake.name()), UserEntity.class, Entity.EntityType.USER);
    Map<String, Long> userNameToUserId =
        userEntities.stream().collect(Collectors.toMap(UserEntity::name, UserEntity::id));

    Map<Long, List<SecurableObject>> roleIdToSecurableObjects =
        getRoleIdToSecurableObjects(metalake.name(), assetNodeById);

    Map<Long, Set<Long>> userIdToRoleIds = getUserIdToRoleIds(userEntities);

    // Fetch tag information relative data
    Set<Long> taggedObjectIds = getTaggedObjectIds(metalake, assetNodeById);
    Set<Long> enabledPolicyObjectIds =
        metricDataService.listEnabledPolicyMetadataObjectIdsByMetalakeId(metalake.id());
    List<PolicyEntity> policies =
        store.list(
            NamespaceUtil.ofPolicy(metalake.name()), PolicyEntity.class, Entity.EntityType.POLICY);
    long disabledPolicyCount = policies.stream().filter(policy -> !policy.enabled()).count();

    return MetalakeSnapshot.builder()
        .assetTreeRoot(root)
        .assetNodeById(assetNodeById)
        .userNameToUserId(userNameToUserId)
        .roleIdToSecurableObjects(roleIdToSecurableObjects)
        .userIdToRoleIds(userIdToRoleIds)
        .taggedObjectIds(taggedObjectIds)
        .catalogNodes(catalogNodes)
        .schemaNodes(schemaNodes)
        .tableNodes(tableNodes)
        .viewNodes(viewNodes)
        .functionNodes(functionNodes)
        .filesetNodes(filesetNodes)
        .topicNodes(topicNodes)
        .modelNodes(modelNodes)
        .enabledPolicyObjectIds(enabledPolicyObjectIds)
        .policyCount(policies.size())
        .disabledPolicyCount(disabledPolicyCount)
        .failedCatalogNames(failedCatalogNames)
        .catalogTypes(catalogTypes)
        .catalogProviders(catalogProviders)
        .viewListingSupportByCatalog(viewListingSupportByCatalog)
        .build();
  }

  private void collectThenCleanMetrics() {
    collectAllMetrics(PublishMode.CURRENT_AND_HISTORY, System.currentTimeMillis());
    cleanExpiredMetrics();
  }

  private static boolean isMetalakeInUse(BaseMetalake metalake) {
    return metalake.properties() == null
        || Boolean.parseBoolean(
            metalake.properties().getOrDefault(Metalake.PROPERTY_IN_USE, Boolean.TRUE.toString()));
  }

  private static boolean isCatalogInUse(CatalogEntity catalog) {
    return catalog.getProperties() == null
        || Boolean.parseBoolean(
            catalog.getProperties().getOrDefault(Catalog.PROPERTY_IN_USE, Boolean.TRUE.toString()));
  }

  private Map<Long, Set<Owner>> getObjectIdToOwners(Long metalakeId) {
    List<OwnerNameRelPO> ownerNameRelPOS =
        metricDataService.listOwnerNameRelsByMetalakeId(metalakeId);
    return ownerNameRelPOS.stream()
        .collect(
            Collectors.groupingBy(
                OwnerNameRelPO::getMetadataObjectId,
                Collectors.mapping(
                    rel ->
                        (Owner)
                            OwnerDTO.builder()
                                .withName(rel.getOwnerName())
                                .withType(Owner.Type.valueOf(rel.getOwnerType()))
                                .build(),
                    Collectors.toSet())));
  }

  private Map<Long, List<SecurableObject>> getRoleIdToSecurableObjects(
      String metalakeName, Map<Long, AssetNode> assetNodeById) throws IOException {
    Map<Long, List<SecurableObject>> roleIdToSecurableObjects = new HashMap<>();
    List<RoleEntity> roleEntities =
        store.list(NamespaceUtil.ofRole(metalakeName), RoleEntity.class, Entity.EntityType.ROLE);
    Set<Long> roleIds = roleEntities.stream().map(RoleEntity::id).collect(Collectors.toSet());
    List<SecurableObjectPO> securableObjectPOs =
        metricDataService.listSecurableObjectsByRoleIds(roleIds);
    for (SecurableObjectPO po : securableObjectPOs) {
      if (!assetNodeById.containsKey(po.getMetadataObjectId())) {
        LOG.warn(
            "Asset node not found for metadataObjectId: {}, skipping securable object type: {}",
            po.getMetadataObjectId(),
            po.getType());
        continue;
      }

      // convert SecurableObjectPO to SecurableObject
      AssetNode assetNode = assetNodeById.get(po.getMetadataObjectId());
      MetadataObject.Type objectType = MetadataObject.Type.valueOf(po.getType());
      MetadataObject metadataObject =
          NameIdentifierUtil.toMetadataObject(
              assetNode.getNameIdent(), MetadataObjectUtil.toEntityType(objectType));
      SecurableObject securableObject =
          POConverters.fromSecurableObjectPO(metadataObject.fullName(), po, objectType);

      roleIdToSecurableObjects
          .computeIfAbsent(po.getRoleId(), k -> new ArrayList<>())
          .add(securableObject);
    }
    return roleIdToSecurableObjects;
  }

  private Map<Long, Set<Long>> getUserIdToRoleIds(List<UserEntity> userEntities) {
    List<UserRoleRelPO> userRoleRels =
        metricDataService.listUserRoleRelsByUserIds(
            userEntities.stream().map(UserEntity::id).collect(Collectors.toSet()));
    return userRoleRels.stream()
        .collect(
            Collectors.groupingBy(
                UserRoleRelPO::getUserId,
                Collectors.mapping(UserRoleRelPO::getRoleId, Collectors.toSet())));
  }

  private Set<Long> getTaggedObjectIds(BaseMetalake metalake, Map<Long, AssetNode> assetNodeById) {
    Set<Long> taggedObjectIds = new HashSet<>();
    List<TagNameMetadataObjectRelPO> tagNameMetadataObjectRelPOS =
        metricDataService.listTagNameMetadataObjectRelsByMetalakeId(metalake.id());
    for (TagNameMetadataObjectRelPO relPO : tagNameMetadataObjectRelPOS) {
      AssetNode assetNode = assetNodeById.get(relPO.getObjectId());
      if (assetNode == null) {
        LOG.warn(
            "Asset node not found for object id: {} in metalake: {} when loading tag relations for tag: {}",
            relPO.getObjectId(),
            metalake.name(),
            relPO.getTagName());
        continue;
      }
      taggedObjectIds.add(assetNode.getId());
    }
    return taggedObjectIds;
  }

  private AssetNode getMetalakeNode(BaseMetalake metalake, Map<Long, Set<Owner>> objectIdToOwners) {
    return new AssetNode(
        metalake.id(),
        metalake.name(),
        MetadataObject.Type.METALAKE,
        null,
        objectIdToOwners.get(metalake.id()));
  }

  private AssetNode getCatalogNode(
      CatalogEntity catalogEntity, AssetNode metalakeNode, Map<Long, Set<Owner>> objectIdToOwners) {
    return new AssetNode(
        catalogEntity.id(),
        catalogEntity.name(),
        MetadataObject.Type.CATALOG,
        metalakeNode,
        objectIdToOwners.get(catalogEntity.id()));
  }

  private CatalogCapabilities loadCatalogCapabilities(CatalogEntity catalog)
      throws CatalogCollectionException {
    try {
      return catalogManager.doWithCatalogWrapper(
          catalog.nameIdentifier(),
          catalogWrapper -> {
            boolean managedSchema = managedStorage(catalogWrapper, Capability.Scope.SCHEMA);
            boolean managedTable = managedStorage(catalogWrapper, Capability.Scope.TABLE);
            boolean managedView = managedStorage(catalogWrapper, Capability.Scope.VIEW);
            boolean managedTopic = managedStorage(catalogWrapper, Capability.Scope.TOPIC);
            boolean hierarchicalSchema = supportsHierarchicalSchema(catalogWrapper);
            boolean viewListingSupported =
                catalog.getType() == Catalog.Type.RELATIONAL
                    && (managedView || supportsViewListing(catalogWrapper));
            return new CatalogCapabilities(
                managedSchema,
                managedTable,
                managedView,
                managedTopic,
                hierarchicalSchema,
                viewListingSupported);
          });
    } catch (CatalogCollectionException e) {
      throw e;
    } catch (Exception e) {
      throw new CatalogCollectionException(e);
    }
  }

  private static boolean managedStorage(
      CatalogManager.CatalogWrapper catalogWrapper, Capability.Scope scope)
      throws CatalogCollectionException {
    try {
      return catalogWrapper.capabilities().managedStorage(scope).supported();
    } catch (Exception e) {
      throw new CatalogCollectionException(e);
    }
  }

  private static boolean supportsHierarchicalSchema(CatalogManager.CatalogWrapper catalogWrapper)
      throws CatalogCollectionException {
    try {
      return catalogWrapper.capabilities().supportsHierarchicalSchema().supported();
    } catch (Exception e) {
      throw new CatalogCollectionException(e);
    }
  }

  private static boolean supportsViewListing(CatalogManager.CatalogWrapper catalogWrapper)
      throws CatalogCollectionException {
    try {
      return catalogWrapper.doWithViewOps(
          viewCatalog ->
              viewCatalog.getClass().getMethod("listViews", Namespace.class).getDeclaringClass()
                  != ViewCatalog.class);
    } catch (UnsupportedOperationException e) {
      return false;
    } catch (Exception e) {
      throw new CatalogCollectionException(e);
    }
  }

  private Set<AssetNode> getSchemaNodes(
      Namespace nsOfSchema,
      AssetNode catalogNode,
      boolean managedSchema,
      boolean hierarchicalSchema,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException, CatalogCollectionException {
    Map<String, SchemaEntity> storedSchemaByName =
        store.list(nsOfSchema, SchemaEntity.class, Entity.EntityType.SCHEMA).stream()
            .collect(Collectors.toMap(SchemaEntity::name, schema -> schema, (left, right) -> left));
    Set<String> schemaNames = new HashSet<>(storedSchemaByName.keySet());

    // if the schemas are not managed, it means there may be external schemas that have not been
    // imported, so we need to use schemaDispatcher to get the full schema
    if (!managedSchema) {
      collectExternalSchemaNames(nsOfSchema, hierarchicalSchema, schemaNames, new HashSet<>());
    }

    String separator = HierarchicalSchemaUtil.schemaSeparator();
    if (hierarchicalSchema) {
      new HashSet<>(schemaNames)
          .forEach(
              schemaName ->
                  schemaNames.addAll(
                      HierarchicalSchemaUtil.getAncestorNames(schemaName, separator)));
    }

    Map<String, AssetNode> schemaNodeByName = new HashMap<>();
    Set<AssetNode> schemaNodes = new HashSet<>();
    List<String> orderedSchemaNames =
        schemaNames.stream()
            .sorted(
                Comparator.comparingInt(
                        (String schemaName) ->
                            HierarchicalSchemaUtil.splitSchemaName(schemaName, separator).length)
                    .thenComparing(Comparator.naturalOrder()))
            .collect(Collectors.toList());
    for (String schemaName : orderedSchemaNames) {
      SchemaEntity storedSchema = storedSchemaByName.get(schemaName);
      long schemaId = storedSchema == null ? -1L : storedSchema.id();
      AssetNode parentNode = catalogNode;
      if (hierarchicalSchema && HierarchicalSchemaUtil.isHierarchical(schemaName, separator)) {
        String parentName = schemaName.substring(0, schemaName.lastIndexOf(separator));
        parentNode = schemaNodeByName.get(parentName);
        if (parentNode == null) {
          throw new CatalogCollectionException(
              new IllegalStateException("Missing parent schema node: " + parentName));
        }
      }

      AssetNode schemaNode =
          new AssetNode(
              schemaId,
              schemaName,
              NameIdentifierUtil.ofSchema(nsOfSchema.level(0), nsOfSchema.level(1), schemaName),
              MetadataObject.Type.SCHEMA,
              parentNode,
              storedSchema == null ? null : objectIdToOwners.get(schemaId));
      parentNode.addChild(schemaNode);
      schemaNodeByName.put(schemaName, schemaNode);
      schemaNodes.add(schemaNode);
      if (storedSchema != null) {
        assetNodeById.put(schemaId, schemaNode);
      }
    }
    return schemaNodes;
  }

  private void collectExternalSchemaNames(
      Namespace namespace,
      boolean hierarchicalSchema,
      Set<String> schemaNames,
      Set<String> visitedParents)
      throws CatalogCollectionException {
    String parentKey = namespace.toString();
    if (!visitedParents.add(parentKey)) {
      return;
    }
    try {
      NameIdentifier[] children = schemaDispatcher.listSchemas(namespace);
      for (NameIdentifier child : children) {
        schemaNames.add(child.name());
        if (hierarchicalSchema) {
          collectExternalSchemaNames(
              Namespace.of(namespace.level(0), namespace.level(1), child.name()),
              true,
              schemaNames,
              visitedParents);
        }
      }
    } catch (Exception e) {
      throw new CatalogCollectionException(e);
    }
  }

  private Set<AssetNode> getTableNodes(
      Namespace nsOfTable,
      AssetNode schemaNode,
      boolean managedTable,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException, CatalogCollectionException {
    Set<AssetNode> tableNodes = new HashSet<>();
    try {
      tableNodes =
          store.list(nsOfTable, TableEntity.class, Entity.EntityType.TABLE).stream()
              .map(
                  table -> {
                    AssetNode assetNode =
                        new AssetNode(
                            table.id(),
                            table.name(),
                            MetadataObject.Type.TABLE,
                            schemaNode,
                            objectIdToOwners.get(table.id()));
                    assetNodeById.put(assetNode.getId(), assetNode);
                    return assetNode;
                  })
              .collect(Collectors.toSet());
    } catch (NoSuchEntityException e) {
      if (managedTable) {
        throw e;
      }
      LOG.warn(
          "Schema not found in store when listing tables for namespace: {}, will try dispatcher",
          nsOfTable,
          e);
    }

    // if the tables are not managed, it means there may be external tables that have not been
    // imported, so we need to use tableDispatcher to get the full tables
    if (!managedTable) {
      try {
        Set<AssetNode> tablesFromDispatcher =
            Arrays.stream(tableDispatcher.listTables(nsOfTable))
                .map(
                    table ->
                        new AssetNode(
                            -1, table.name(), MetadataObject.Type.TABLE, schemaNode, null))
                .collect(Collectors.toSet());
        tableNodes.addAll(tablesFromDispatcher);
      } catch (Exception e) {
        throw new CatalogCollectionException(e);
      }
    }
    return tableNodes;
  }

  private Set<AssetNode> getViewNodes(
      Namespace nsOfView,
      AssetNode schemaNode,
      boolean managedView,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException, CatalogCollectionException {
    Set<AssetNode> viewNodes = new HashSet<>();
    try {
      viewNodes =
          store.list(nsOfView, ViewEntity.class, Entity.EntityType.VIEW).stream()
              .map(
                  view -> {
                    AssetNode assetNode =
                        new AssetNode(
                            view.id(),
                            view.name(),
                            MetadataObject.Type.VIEW,
                            schemaNode,
                            objectIdToOwners.get(view.id()));
                    assetNodeById.put(assetNode.getId(), assetNode);
                    return assetNode;
                  })
              .collect(Collectors.toSet());
    } catch (NoSuchEntityException e) {
      if (managedView) {
        throw e;
      }
      LOG.warn(
          "Schema not found in store when listing views for namespace: {}, will try dispatcher",
          nsOfView,
          e);
    }

    if (!managedView) {
      try {
        Set<AssetNode> viewsFromDispatcher =
            Arrays.stream(viewDispatcher.listViews(nsOfView))
                .map(
                    view ->
                        new AssetNode(-1, view.name(), MetadataObject.Type.VIEW, schemaNode, null))
                .collect(Collectors.toSet());
        viewNodes.addAll(viewsFromDispatcher);
      } catch (Exception e) {
        throw new CatalogCollectionException(e);
      }
    }
    return viewNodes;
  }

  private Set<AssetNode> getFilesetNodes(
      Namespace nsOfFileset,
      AssetNode schemaNode,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
    return store.list(nsOfFileset, FilesetEntity.class, Entity.EntityType.FILESET).stream()
        .map(
            fileset -> {
              AssetNode assetNode =
                  new AssetNode(
                      fileset.id(),
                      fileset.name(),
                      MetadataObject.Type.FILESET,
                      schemaNode,
                      objectIdToOwners.get(fileset.id()));
              assetNodeById.put(assetNode.getId(), assetNode);
              return assetNode;
            })
        .collect(Collectors.toSet());
  }

  private Set<AssetNode> getFunctionNodes(
      Namespace nsOfFunction,
      AssetNode schemaNode,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
    return store.list(nsOfFunction, FunctionEntity.class, Entity.EntityType.FUNCTION).stream()
        .map(
            function -> {
              AssetNode assetNode =
                  new AssetNode(
                      function.id(),
                      function.name(),
                      MetadataObject.Type.FUNCTION,
                      schemaNode,
                      objectIdToOwners.get(function.id()));
              assetNodeById.put(assetNode.getId(), assetNode);
              return assetNode;
            })
        .collect(Collectors.toSet());
  }

  private Set<AssetNode> getTopicNodes(
      Namespace nsOfTopic,
      AssetNode schemaNode,
      boolean managedTopic,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException, CatalogCollectionException {
    Set<AssetNode> topicNodes = new HashSet<>();
    try {
      topicNodes =
          store.list(nsOfTopic, TopicEntity.class, Entity.EntityType.TOPIC).stream()
              .map(
                  topic -> {
                    AssetNode assetNode =
                        new AssetNode(
                            topic.id(),
                            topic.name(),
                            MetadataObject.Type.TOPIC,
                            schemaNode,
                            objectIdToOwners.get(topic.id()));
                    assetNodeById.put(assetNode.getId(), assetNode);
                    return assetNode;
                  })
              .collect(Collectors.toSet());
    } catch (NoSuchEntityException e) {
      if (managedTopic) {
        throw e;
      }
      LOG.warn(
          "Schema not found in store when listing topics for namespace: {}, will try dispatcher",
          nsOfTopic,
          e);
    }

    // if the topics are not managed, it means there may be external topics that have not been
    // imported, so we need to use topicDispatcher to get the full topics
    if (!managedTopic) {
      try {
        Set<AssetNode> topicsFromDispatcher =
            Arrays.stream(topicDispatcher.listTopics(nsOfTopic))
                .map(
                    topic ->
                        new AssetNode(
                            -1, topic.name(), MetadataObject.Type.TOPIC, schemaNode, null))
                .collect(Collectors.toSet());
        topicNodes.addAll(topicsFromDispatcher);
      } catch (Exception e) {
        throw new CatalogCollectionException(e);
      }
    }
    return topicNodes;
  }

  private Set<AssetNode> getModelNodes(
      Namespace nsOfModel,
      AssetNode schemaNode,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
    return store.list(nsOfModel, ModelEntity.class, Entity.EntityType.MODEL).stream()
        .map(
            model -> {
              AssetNode assetNode =
                  new AssetNode(
                      model.id(),
                      model.name(),
                      MetadataObject.Type.MODEL,
                      schemaNode,
                      objectIdToOwners.get(model.id()));
              assetNodeById.put(assetNode.getId(), assetNode);
              return assetNode;
            })
        .collect(Collectors.toSet());
  }

  private static class CatalogCollectionException extends Exception {
    private CatalogCollectionException(Throwable cause) {
      super(cause);
    }
  }

  /** Capability flags read from a catalog while its lease is held. */
  private static class CatalogCapabilities {
    private final boolean managedSchema;
    private final boolean managedTable;
    private final boolean managedView;
    private final boolean managedTopic;
    private final boolean hierarchicalSchema;
    private final boolean viewListingSupported;

    private CatalogCapabilities(
        boolean managedSchema,
        boolean managedTable,
        boolean managedView,
        boolean managedTopic,
        boolean hierarchicalSchema,
        boolean viewListingSupported) {
      this.managedSchema = managedSchema;
      this.managedTable = managedTable;
      this.managedView = managedView;
      this.managedTopic = managedTopic;
      this.hierarchicalSchema = hierarchicalSchema;
      this.viewListingSupported = viewListingSupported;
    }
  }
}
