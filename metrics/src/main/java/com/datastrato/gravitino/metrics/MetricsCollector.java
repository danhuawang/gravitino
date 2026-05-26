/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

// We have placed this class in the server module rather than the core module because later
// authorization relies on
// org.apache.gravitino.server.authorization.MetadataFilterHelper#filterByPrivilege in the
// server-common.
package com.datastrato.gravitino.metrics;

import static com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService.Metric.TAGGED_ASSET_COUNT;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCollector implements Closeable {
  public static final Long MOCK_USER_ID_FOR_DISABLE_AUTHZ = 0L;
  public static final Long MOCK_USER_ID_FOR_METALAKE_OWNER = 1L;

  public static MetricsCollector getInstance() {
    return INSTANCE;
  }

  private static final MetricsCollector INSTANCE = new MetricsCollector();
  private static final Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

  private CatalogManager catalogManager;
  private SchemaDispatcher schemaDispatcher;
  private TableDispatcher tableDispatcher;
  private TopicDispatcher topicDispatcher;
  private MetricDataService metricDataService;
  private EntityStore store;

  // Scheduled executor for periodic metric collection
  private ScheduledThreadPoolExecutor scheduledExecutor;
  private int scheduledPeriodDays = 1;

  // Executor for parallel computation of metrics, create independent thread for each metalake
  private ExecutorService metricsCalculationExecutor;
  private final int metricsCalculationCoreThreadsNum = 2;
  private final int metricsCalculationMaxThreadsNum = 5;
  private final long metricsCalculationKeepAliveTimeSec = 60L;
  private final int metricsCalculationQueueSize = 50;

  @Getter
  private final EnumMap<TagCategory, Set<String>> categoryToTagNames =
      new EnumMap<>(TagCategory.class);

  private Duration retentionPeriod;
  private boolean enableAuthorization;

  @Getter private final Map<String, MetalakeSnapshot> metalakeSnapshots = new HashMap<>();

  private ZonedDateTime batchDate;

  private MetricsCollector() {}

  public void initialize(ServerConfig serverConfig, GravitinoEnv gravitinoEnv) {
    this.catalogManager = gravitinoEnv.catalogManager();
    this.schemaDispatcher = gravitinoEnv.schemaDispatcher();
    this.tableDispatcher = gravitinoEnv.tableDispatcher();
    this.topicDispatcher = gravitinoEnv.topicDispatcher();
    this.metricDataService = MetricDataService.getInstance();
    metricDataService.initialize(serverConfig.get(Configs.ENABLE_AUTHORIZATION));
    this.store = gravitinoEnv.entityStore();

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

    Set<String> piiTags =
        serverConfig.get(MetricsConfig.PII_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    Set<String> publicTags =
        serverConfig.get(MetricsConfig.PUBLIC_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    Set<String> confidentialTags =
        serverConfig.get(MetricsConfig.CONFIDENTIAL_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    Set<String> privateTags =
        serverConfig.get(MetricsConfig.PRIVATE_TAGS_CONFIG).stream()
            .map(String::trim)
            .collect(Collectors.toSet());

    categoryToTagNames.put(TagCategory.PII, piiTags);
    categoryToTagNames.put(TagCategory.PUBLIC, publicTags);
    categoryToTagNames.put(TagCategory.CONFIDENTIAL, confidentialTags);
    categoryToTagNames.put(TagCategory.PRIVATE, privateTags);

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

  public enum TagCategory {
    PUBLIC,
    PII,
    CONFIDENTIAL,
    PRIVATE,
    // Used to calculate the total number of tagged assets
    ANY_TAG;

    public String metricName() {
      if (this == ANY_TAG) {
        return TAGGED_ASSET_COUNT.getName();
      }
      return this.name().toLowerCase() + "_tagged_asset_count";
    }
  }

  private void collectAllMetrics() {
    try {
      LOG.info(
          "[batch: {}] Starting to collect metrics data for all users in all metalakes", batchDate);
      List<BaseMetalake> metalakes =
          store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE);

      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (BaseMetalake metalake : metalakes) {
        String metalakeName = metalake.name();
        metalakeSnapshots.put(metalakeName, loadAllDataForMetalake(metalake));

        // create independent calculation tasks for each metalake
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
      LOG.info(
          "Expired metrics cleaned successfully, older than: {}",
          Instant.ofEpochMilli(oldestTimestamp));

      metricDataService.cleanInvalidMetrics();
    } catch (Exception e) {
      LOG.error("Failed to clean expired metrics", e);
    }
  }

  private void calculateMetricsForMetalake(String metalakeName) {
    LOG.info("[batch: {}] Starting to process metrics for metalake: {}", batchDate, metalakeName);

    try {
      MetalakeSnapshot metalakeSnapshot = metalakeSnapshots.get(metalakeName);
      MetricsCalculator calculator = new MetricsCalculator(metalakeSnapshot, categoryToTagNames);

      long metalakeId = metalakeSnapshot.getAssetTreeRoot().getId();
      if (!enableAuthorization) {
        List<MetricPO> metrics = calculator.calculateMetricsForDisableAuthz();
        metricDataService.insertMetrics(metalakeId, MOCK_USER_ID_FOR_DISABLE_AUTHZ, metrics);
      } else {
        for (String userName : metalakeSnapshot.getUserNameToUserId().keySet()) {
          List<MetricPO> metrics =
              calculator.calculateMetricsForUser(userName, enableAuthorization, false);
          Long userId = metalakeSnapshot.getUserNameToUserId().get(userName);
          metricDataService.insertMetrics(metalakeId, userId, metrics);
        }
        Optional<Owner> owner =
            metalakeSnapshot.getAssetTreeRoot().getOwners().stream()
                .filter(o -> o.type().equals(Owner.Type.USER))
                .filter(o -> metalakeSnapshot.getUserNameToUserId().containsKey(o.name()))
                .findFirst();
        if (owner.isPresent()) {
          String ownerName = owner.get().name();
          List<MetricPO> metrics =
              calculator.calculateMetricsForUser(ownerName, enableAuthorization, true);
          metricDataService.insertMetrics(metalakeId, MOCK_USER_ID_FOR_METALAKE_OWNER, metrics);
        }
      }

      LOG.info("[metrics] Metrics for metalake {} processed successfully.", metalakeName);
    } catch (Exception e) {
      LOG.error("[metrics] Failed to calculate metrics for metalake: {}", metalakeName, e);
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
    Set<AssetNode> filesetNodes = new HashSet<>();
    Set<AssetNode> topicNodes = new HashSet<>();
    Set<AssetNode> modelNodes = new HashSet<>();
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
      AssetNode catalogNode = getCatalogNode(catalog, root, objectIdToOwners);

      root.addChild(catalogNode);
      assetNodeById.put(catalogNode.getId(), catalogNode);
      catalogNodes.add(catalogNode);

      CatalogManager.CatalogWrapper catalogWrapper =
          catalogManager.loadCatalogAndWrap(catalog.nameIdentifier());
      boolean managedSchema =
          catalogWrapper.capabilities().managedStorage(Capability.Scope.SCHEMA).supported();

      // fetch and add schemas
      Namespace nsOfSchema = NamespaceUtil.ofSchema(metalake.name(), catalog.name());
      Set<AssetNode> schemas =
          getSchemaNodes(nsOfSchema, catalogNode, managedSchema, objectIdToOwners, assetNodeById);
      catalogNode.addChildren(schemas);
      schemaNodes.addAll(schemas);

      // for each schema, fetch and add tables/filesets/topics/models
      boolean managedTable =
          catalogWrapper.capabilities().managedStorage(Capability.Scope.TABLE).supported();
      boolean managedTopic =
          catalogWrapper.capabilities().managedStorage(Capability.Scope.TOPIC).supported();

      for (AssetNode schemaNode : catalogNode.getChildren()) {
        Namespace ns = Namespace.of(metalake.name(), catalogNode.getName(), schemaNode.getName());
        switch (catalog.getType()) {
          case RELATIONAL:
            Set<AssetNode> tables =
                getTableNodes(ns, schemaNode, managedTable, objectIdToOwners, assetNodeById);
            schemaNode.addChildren(tables);
            tableNodes.addAll(tables);
            break;

          case FILESET:
            Set<AssetNode> filesets =
                getFilesetNodes(ns, schemaNode, objectIdToOwners, assetNodeById);
            schemaNode.addChildren(filesets);
            filesetNodes.addAll(filesets);
            break;

          case MESSAGING:
            Set<AssetNode> topics =
                getTopicNodes(ns, schemaNode, managedTopic, objectIdToOwners, assetNodeById);
            schemaNode.addChildren(topics);
            topicNodes.addAll(topics);
            break;

          case MODEL:
            Set<AssetNode> models = getModelNodes(ns, schemaNode, objectIdToOwners, assetNodeById);
            schemaNode.addChildren(models);
            modelNodes.addAll(models);
            break;

          default:
            LOG.warn("Unsupported catalog type: {}", catalog.getType());
        }
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
    Map<NameIdentifier, Set<String>> assetIdentToTagNames =
        getAssetIdentToTagNames(metalake, assetNodeById);
    long tagCount = metricDataService.getTagCountByMetalakeId(metalake.id());

    return new MetalakeSnapshot(
        root,
        assetNodeById,
        userNameToUserId,
        roleIdToSecurableObjects,
        userIdToRoleIds,
        tagCount,
        assetIdentToTagNames,
        catalogNodes,
        schemaNodes,
        tableNodes,
        filesetNodes,
        topicNodes,
        modelNodes);
  }

  private void collectThenCleanMetrics() {
    batchDate = batchDate.plusDays(1);
    collectAllMetrics();
    cleanExpiredMetrics();
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

  private Map<NameIdentifier, Set<String>> getAssetIdentToTagNames(
      BaseMetalake metalake, Map<Long, AssetNode> assetNodeById) {
    Map<NameIdentifier, Set<String>> assetIdentToTagNames = new HashMap<>();
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
      assetIdentToTagNames
          .computeIfAbsent(assetNode.getNameIdent(), k -> new HashSet<>())
          .add(relPO.getTagName());
    }
    return assetIdentToTagNames;
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

  private Set<AssetNode> getSchemaNodes(
      Namespace nsOfSchema,
      AssetNode catalogNode,
      boolean managedSchema,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
    Set<AssetNode> schemaNodes =
        store.list(nsOfSchema, SchemaEntity.class, Entity.EntityType.SCHEMA).stream()
            .map(
                schema -> {
                  AssetNode assetNode =
                      new AssetNode(
                          schema.id(),
                          schema.name(),
                          MetadataObject.Type.SCHEMA,
                          catalogNode,
                          objectIdToOwners.get(schema.id()));
                  assetNodeById.put(assetNode.getId(), assetNode);
                  return assetNode;
                })
            .collect(Collectors.toSet());

    // if the schemas are not managed, it means there may be external schemas that have not been
    // imported, so we need to use schemaDispatcher to get the full schema
    if (!managedSchema) {
      Set<AssetNode> schemasFromDispatcher =
          Arrays.stream(schemaDispatcher.listSchemas(nsOfSchema))
              .map(
                  schema ->
                      new AssetNode(
                          -1, schema.name(), MetadataObject.Type.SCHEMA, catalogNode, null))
              .collect(Collectors.toSet());
      schemaNodes.addAll(schemasFromDispatcher);
    }
    return schemaNodes;
  }

  private Set<AssetNode> getTableNodes(
      Namespace nsOfTable,
      AssetNode schemaNode,
      boolean managedTable,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
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
      LOG.warn(
          "[batch: {}] Schema not found in store when listing tables for namespace: {}, will try dispatcher",
          batchDate,
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
        LOG.warn(
            "[batch: {}] Failed to list tables from dispatcher for namespace: {}",
            batchDate,
            nsOfTable,
            e);
      }
    }
    return tableNodes;
  }

  private Set<AssetNode> getFilesetNodes(
      Namespace nsOfFileset,
      AssetNode schemaNode,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
    try {
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
    } catch (NoSuchEntityException e) {
      LOG.warn(
          "[batch: {}] Schema not found in store when listing filesets for namespace: {}",
          batchDate,
          nsOfFileset,
          e);
      return new HashSet<>();
    }
  }

  private Set<AssetNode> getTopicNodes(
      Namespace nsOfTopic,
      AssetNode schemaNode,
      boolean managedTopic,
      Map<Long, Set<Owner>> objectIdToOwners,
      Map<Long, AssetNode> assetNodeById)
      throws IOException {
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
      LOG.warn(
          "[batch: {}] Schema not found in store when listing topics for namespace: {}, will try dispatcher",
          batchDate,
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
        LOG.warn(
            "[batch: {}] Failed to list topics from dispatcher for namespace: {}",
            batchDate,
            nsOfTopic,
            e);
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
    try {
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
    } catch (NoSuchEntityException e) {
      LOG.warn(
          "[batch: {}] Schema not found in store when listing models for namespace: {}",
          batchDate,
          nsOfModel,
          e);
      return new HashSet<>();
    }
  }
}
