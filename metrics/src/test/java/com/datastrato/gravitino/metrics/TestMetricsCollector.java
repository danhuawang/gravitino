/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.OwnerNameRelPO;
import com.datastrato.gravitino.metrics.storage.relational.TagNameMetadataObjectRelPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.ThrowableFunction;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TestMetricsCollector {

  private static final LockManager LOCKS = new LockManager(new ServerConfig());

  private ServerConfig serverConfig;
  private GravitinoEnv gravitinoEnv;
  private MetalakeDispatcher metalakeDispatcher;
  private CatalogDispatcher catalogDispatcher;
  private CatalogManager catalogManager;
  private CatalogManager.CatalogWrapper catalogWrapper;
  private SchemaDispatcher schemaDispatcher;
  private TableDispatcher tableDispatcher;
  private FilesetDispatcher filesetDispatcher;
  private TopicDispatcher topicDispatcher;
  private ViewDispatcher viewDispatcher;
  private ModelDispatcher modelDispatcher;
  private TagDispatcher tagDispatcher;
  private AccessControlDispatcher accessControlDispatcher;
  private OwnerDispatcher ownerDispatcher;
  private MetricDataService metricDataService;
  private EntityStore store;

  private final long mockId = 1L;
  private final String metalakeName = "test_metalake";
  private final String relationalCatalogName = "rel_catalog";
  private final String relationalCatalogProvider = "jdbc-postgresql";
  private final String relationalSchemaName = "rel_schema";
  private final String tableName = "table";

  @BeforeEach
  public void setUp() throws Exception {
    MetricsCollector.getInstance().getMetalakeSnapshots().clear();
    serverConfig = mock(ServerConfig.class);
    metalakeDispatcher = mock(MetalakeDispatcher.class);
    catalogManager = mock(CatalogManager.class);
    catalogDispatcher = mock(CatalogDispatcher.class);
    schemaDispatcher = mock(SchemaDispatcher.class);
    tableDispatcher = mock(TableDispatcher.class);
    filesetDispatcher = mock(FilesetDispatcher.class);
    topicDispatcher = mock(TopicDispatcher.class);
    viewDispatcher = mock(ViewDispatcher.class);
    modelDispatcher = mock(ModelDispatcher.class);
    tagDispatcher = mock(TagDispatcher.class);
    accessControlDispatcher = mock(AccessControlDispatcher.class);
    ownerDispatcher = mock(OwnerDispatcher.class);
    metricDataService = mock(MetricDataService.class);
    store = mock(EntityStore.class);
    gravitinoEnv = mock(GravitinoEnv.class);
    when(gravitinoEnv.metalakeDispatcher()).thenReturn(metalakeDispatcher);
    when(gravitinoEnv.catalogDispatcher()).thenReturn(catalogDispatcher);
    when(gravitinoEnv.schemaDispatcher()).thenReturn(schemaDispatcher);
    when(gravitinoEnv.tableDispatcher()).thenReturn(tableDispatcher);
    when(gravitinoEnv.filesetDispatcher()).thenReturn(filesetDispatcher);
    when(gravitinoEnv.topicDispatcher()).thenReturn(topicDispatcher);
    when(gravitinoEnv.viewDispatcher()).thenReturn(viewDispatcher);
    when(gravitinoEnv.modelDispatcher()).thenReturn(modelDispatcher);
    when(gravitinoEnv.tagDispatcher()).thenReturn(tagDispatcher);
    when(gravitinoEnv.accessControlDispatcher()).thenReturn(accessControlDispatcher);
    when(gravitinoEnv.ownerDispatcher()).thenReturn(ownerDispatcher);
    when(gravitinoEnv.entityStore()).thenReturn(store);
    when(gravitinoEnv.catalogManager()).thenReturn(catalogManager);

    // mock config
    when(serverConfig.get(MetricsConfig.RETENTION_DAYS_CONFIG)).thenReturn(30);
    when(serverConfig.get(MetricsConfig.AUTHORIZATION_CALCULATION_THREADS_CONFIG)).thenReturn(2);
    when(serverConfig.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(false);

    // mock store
    mockListCatalogFromStore();
    mockLoadCatalog();
    mockListUserFromStore();
    mockLoadRoleSecurableObjectsRel();
    mockListOwners();
    mockSchemaDispatcher();
    mockTableDispatcher();
    mockViewDispatcher();
    mockListFunctionsFromStore();
    when(metricDataService.listEnabledPolicyMetadataObjectIdsByMetalakeId(mockId))
        .thenReturn(Collections.emptySet());
    when(metricDataService.listAssociatedTagCountsByCatalogId(mockId))
        .thenReturn(ImmutableMap.of(mockId, 2L));
    when(metricDataService.listAssociatedPolicyCountsByCatalogId(mockId))
        .thenReturn(ImmutableMap.of(mockId, 1L));
    PolicyEntity enabledPolicy1 = mock(PolicyEntity.class);
    PolicyEntity enabledPolicy2 = mock(PolicyEntity.class);
    PolicyEntity disabledPolicy = mock(PolicyEntity.class);
    when(enabledPolicy1.enabled()).thenReturn(true);
    when(enabledPolicy2.enabled()).thenReturn(true);
    when(disabledPolicy.enabled()).thenReturn(false);
    when(store.list(
            eq(NamespaceUtil.ofPolicy(metalakeName)),
            eq(PolicyEntity.class),
            eq(Entity.EntityType.POLICY)))
        .thenReturn(ImmutableList.of(enabledPolicy1, enabledPolicy2, disabledPolicy));
  }

  @Test
  void testLoadAllDataForMetalake() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);

      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      BaseMetalake metalake =
          BaseMetalake.builder()
              .withId(mockId)
              .withName(metalakeName)
              .withVersion(SchemaVersion.V_0_1)
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build();

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake);
      assertNotNull(snapshot);
      assertEquals(metalakeName, snapshot.getAssetTreeRoot().getName());
      assertEquals(1, snapshot.getAssetTreeRoot().getChildren().size());
      assertEquals(1, snapshot.getCatalogNodes().size());
      assertEquals(1, snapshot.getSchemaNodes().size());
      assertEquals(
          snapshot.getSchemaNodes(), snapshot.getCatalogNodes().iterator().next().getChildren());
      assertEquals(1, snapshot.getTableNodes().size());
      assertEquals(1, snapshot.getViewNodes().size());
      assertEquals(0, snapshot.getFunctionNodes().size());
      assertEquals(
          relationalCatalogProvider, snapshot.getCatalogProviders().get(relationalCatalogName));
      assertEquals(2, snapshot.getSchemaNodes().iterator().next().getChildren().size());
      assertEquals(3, snapshot.getPolicyCount());
      assertEquals(1, snapshot.getDisabledPolicyCount());
      assertEquals(2L, snapshot.getAssociatedTagCountsByCatalogId().get(mockId));
      assertEquals(1L, snapshot.getAssociatedPolicyCountsByCatalogId().get(mockId));
    }
  }

  @Test
  void testLoadAllDataForMetalakeIncludesFunctions() throws Exception {
    mockStoredRelationalSchema(41L);
    FunctionEntity function = mock(FunctionEntity.class);
    when(function.id()).thenReturn(42L);
    when(function.name()).thenReturn("function");
    when(store.list(
            eq(NamespaceUtil.ofFunction(metalakeName, relationalCatalogName, relationalSchemaName)),
            eq(FunctionEntity.class),
            eq(Entity.EntityType.FUNCTION)))
        .thenReturn(ImmutableList.of(function));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);

      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake());

      assertEquals(41L, snapshot.getSchemaNodes().iterator().next().getId());
      assertEquals(1, snapshot.getFunctionNodes().size());
      AssetNode functionNode = snapshot.getFunctionNodes().iterator().next();
      assertEquals(42L, functionNode.getId());
      assertEquals("function", functionNode.getName());
      assertEquals(MetadataObject.Type.FUNCTION, functionNode.getType());
      assertEquals(3, snapshot.getSchemaNodes().iterator().next().getChildren().size());

      List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
      assertMetric(metrics, "asset_count", 3.0, MetricState.COMPLETE);
      assertMetric(metrics, "by_asset_type::FUNCTION::asset_count", 1.0, MetricState.COMPLETE);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void testMissingTagRelationsAggregateWarningsPerCollection(int missingRelationCount)
      throws Exception {
    mockAssetInventory(Capability.Scope.TABLE, "retained", "stale", "retained");
    List<TagNameMetadataObjectRelPO> relations = new ArrayList<>();
    relations.add(
        TagNameMetadataObjectRelPO.builder()
            .withObjectId(42L)
            .withTagName("classification")
            .build());
    for (int i = 0; i < missingRelationCount; i++) {
      relations.add(
          TagNameMetadataObjectRelPO.builder()
              .withObjectId(43L)
              .withTagName("stale_tag_" + i)
              .build());
    }
    when(metricDataService.listTagNameMetadataObjectRelsByMetalakeId(mockId)).thenReturn(relations);

    LoggerContext context =
        (LoggerContext) LogManager.getContext(MetricsCollector.class.getClassLoader(), false);
    Configuration configuration = context.getConfiguration();
    String loggerName = MetricsCollector.class.getName();
    LoggerConfig previousConfig = configuration.getLoggers().get(loggerName);
    Appender appender = mock(Appender.class);
    when(appender.getName()).thenReturn("metricsTagRelationLogs");
    when(appender.isStarted()).thenReturn(true);
    List<LogEvent> events = new ArrayList<>();
    doAnswer(
            invocation -> {
              LogEvent event = invocation.getArgument(0);
              events.add(event.toImmutable());
              return null;
            })
        .when(appender)
        .append(any(LogEvent.class));
    LoggerConfig loggerConfig = new LoggerConfig(loggerName, Level.DEBUG, false);
    loggerConfig.addAppender(appender, Level.DEBUG, null);
    configuration.removeLogger(loggerName);
    configuration.addLogger(loggerName, loggerConfig);
    context.updateLoggers();
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        events.clear();
        MetalakeSnapshot snapshot = collectSnapshot();

        assertTrue(snapshot.getFailedCatalogNames().isEmpty());
        assertEquals(Collections.singleton(42L), snapshot.getTaggedObjectIds());
        List<String> warnings =
            events.stream()
                .filter(event -> Level.WARN.equals(event.getLevel()))
                .map(event -> event.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
        assertEquals(
            missingRelationCount == 0
                ? Collections.emptyList()
                : Collections.singletonList(
                    "Skipped 2 tag relations with unavailable asset nodes while collecting dashboard"
                        + " metrics for metalake "
                        + metalakeName),
            warnings);
        assertEquals(
            missingRelationCount,
            events.stream()
                .filter(event -> Level.DEBUG.equals(event.getLevel()))
                .filter(
                    event ->
                        event.getMessage().getFormattedMessage().contains("loading tag relations"))
                .count());
      }
    } finally {
      configuration.removeLogger(loggerName);
      if (previousConfig != null) {
        configuration.addLogger(loggerName, previousConfig);
      }
      context.updateLoggers();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Capability.Scope.class,
      names = {"TABLE", "VIEW", "TOPIC"})
  void testUnmanagedAssetsUseSourceInventoryAndRetainGovernance(Capability.Scope scope)
      throws Exception {
    mockAssetInventory(scope, "retained", "stale", "retained", "new_asset");
    mockAssetGovernance(scope);

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    Set<AssetNode> assets = snapshot.getSchemaNodes().iterator().next().getChildren();
    assertEquals(
        Set.of("retained", "new_asset"),
        assets.stream().map(AssetNode::getName).collect(Collectors.toSet()));
    AssetNode retained = snapshot.getAssetNodeById().get(42L);
    assertNotNull(retained);
    assertTrue(assets.contains(retained));
    assertEquals("owner", retained.getOwners().iterator().next().name());
    assertFalse(snapshot.getAssetNodeById().containsKey(43L));
    assertFalse(snapshot.getAssetNodeById().containsKey(-1L));
    AssetNode newAsset =
        assets.stream()
            .filter(node -> node.getName().equals("new_asset"))
            .findFirst()
            .orElseThrow();
    assertEquals(-1L, newAsset.getId());
    assertEquals(MetadataObject.Type.valueOf(scope.name()), newAsset.getType());
    assertTrue(newAsset.getOwners().isEmpty());
    assertEquals(Collections.singleton(42L), snapshot.getTaggedObjectIds());

    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    assertMetric(metrics, "asset_count", 2.0, MetricState.COMPLETE);
    assertMetric(metrics, "by_asset_type::" + scope + "::asset_count", 2.0, MetricState.COMPLETE);
    assertMetric(metrics, "owned_asset_count", 1.0, MetricState.COMPLETE);
    assertMetric(metrics, "tagged_asset_count", 1.0, MetricState.COMPLETE);
    assertMetric(metrics, "policy_covered_asset_count", 1.0, MetricState.COMPLETE);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forSchema(relationalCatalogName, relationalSchemaName),
        2.0,
        MetricState.COMPLETE);
    verify(store, never()).delete(any(), any(), eq(true));
  }

  @ParameterizedTest
  @EnumSource(
      value = Capability.Scope.class,
      names = {"TABLE", "VIEW", "TOPIC"})
  void testUnmanagedAssetsMatchNormalizedSourceNamesAndRetainGovernance(Capability.Scope scope)
      throws Exception {
    mockAssetInventory(scope, "orders", "stale", "Orders", "NewAsset");
    mockAssetGovernance(scope);
    AtomicBoolean catalogLeaseHeld = new AtomicBoolean();
    doAnswer(
            invocation -> {
              ThrowableFunction<CatalogManager.CatalogWrapper, Object> operation =
                  invocation.getArgument(1);
              catalogLeaseHeld.set(true);
              try {
                return operation.apply(catalogWrapper);
              } finally {
                catalogLeaseHeld.set(false);
              }
            })
        .when(catalogManager)
        .doWithCatalogWrapper(
            eq(NameIdentifierUtil.ofCatalog(metalakeName, relationalCatalogName)), any());
    when(catalogWrapper.capabilities())
        .thenReturn(
            new Capability() {
              @Override
              public String normalizeName(Scope requestedScope, String name) {
                assertTrue(catalogLeaseHeld.get(), "Normalization must hold the catalog lease");
                return Capability.super.normalizeName(requestedScope, name);
              }

              @Override
              public CapabilityResult caseSensitiveOnName(Scope requestedScope) {
                return requestedScope == scope
                    ? CapabilityResult.unsupported("case-insensitive asset names")
                    : Capability.DEFAULT.caseSensitiveOnName(requestedScope);
              }
            });

    MetalakeSnapshot snapshot = collectSnapshot();

    assertFalse(catalogLeaseHeld.get());
    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    Set<AssetNode> assets = snapshot.getSchemaNodes().iterator().next().getChildren();
    assertEquals(
        Set.of("orders", "NewAsset"),
        assets.stream().map(AssetNode::getName).collect(Collectors.toSet()));
    AssetNode retained = snapshot.getAssetNodeById().get(42L);
    assertNotNull(retained);
    assertTrue(assets.contains(retained));
    assertEquals("owner", retained.getOwners().iterator().next().name());
    assertFalse(snapshot.getAssetNodeById().containsKey(43L));
    assertFalse(snapshot.getAssetNodeById().containsKey(-1L));
    AssetNode newAsset =
        assets.stream().filter(node -> node.getName().equals("NewAsset")).findFirst().orElseThrow();
    assertEquals(-1L, newAsset.getId());
    assertTrue(newAsset.getOwners().isEmpty());
    assertEquals(Collections.singleton(42L), snapshot.getTaggedObjectIds());
    List<SecurableObject> securableObjects = snapshot.getRoleIdToSecurableObjects().get(51L);
    assertEquals(1, securableObjects.size());
    assertEquals(
        relationalCatalogName + "." + relationalSchemaName + ".orders",
        securableObjects.get(0).fullName());
    assertEquals(MetadataObject.Type.valueOf(scope.name()), securableObjects.get(0).type());
    assertEquals(Privilege.Name.MANAGE_GRANTS, securableObjects.get(0).privileges().get(0).name());

    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    assertMetric(metrics, "asset_count", 2.0, MetricState.COMPLETE);
    assertMetric(metrics, "by_asset_type::" + scope + "::asset_count", 2.0, MetricState.COMPLETE);
    assertMetric(metrics, "owned_asset_count", 1.0, MetricState.COMPLETE);
    assertMetric(metrics, "tagged_asset_count", 1.0, MetricState.COMPLETE);
    assertMetric(metrics, "policy_covered_asset_count", 1.0, MetricState.COMPLETE);
  }

  @ParameterizedTest
  @EnumSource(
      value = Capability.Scope.class,
      names = {"TABLE", "VIEW", "TOPIC"})
  void testManagedAssetsUseStoredInventory(Capability.Scope scope) throws Exception {
    mockAssetInventory(scope, "retained", "stored_only", "retained", "external_only");
    when(catalogWrapper.capabilities())
        .thenReturn(
            new Capability() {
              @Override
              public CapabilityResult managedStorage(Scope requestedScope) {
                return requestedScope == scope
                    ? CapabilityResult.SUPPORTED
                    : Capability.DEFAULT.managedStorage(requestedScope);
              }
            });

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(
        Set.of("retained", "stored_only"),
        snapshot.getSchemaNodes().iterator().next().getChildren().stream()
            .map(AssetNode::getName)
            .collect(Collectors.toSet()));
    assertTrue(snapshot.getAssetNodeById().containsKey(42L));
    assertTrue(snapshot.getAssetNodeById().containsKey(43L));
    assertMetric(
        new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz(),
        "by_asset_type::" + scope + "::asset_count",
        2.0,
        MetricState.COMPLETE);
    switch (scope) {
      case TABLE:
        verify(tableDispatcher, never()).listTables(any());
        break;
      case VIEW:
        verify(viewDispatcher, never()).listViews(any());
        break;
      case TOPIC:
        verify(topicDispatcher, never()).listTopics(any());
        break;
      default:
        throw new AssertionError("Unexpected scope: " + scope);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Capability.Scope.class,
      names = {"TABLE", "VIEW", "TOPIC"})
  void testEmptySourceInventoryExcludesStoredAssets(Capability.Scope scope) throws Exception {
    mockAssetInventory(scope, "retained", "stale");

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertTrue(snapshot.getSchemaNodes().iterator().next().getChildren().isEmpty());
    assertFalse(snapshot.getAssetNodeById().containsKey(42L));
    assertFalse(snapshot.getAssetNodeById().containsKey(43L));
    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    assertMetric(metrics, "asset_count", 0.0, MetricState.COMPLETE);
    assertMetric(
        metrics,
        DirectChildCountMetricNames.forSchema(relationalCatalogName, relationalSchemaName),
        0.0,
        MetricState.COMPLETE);
  }

  @ParameterizedTest
  @EnumSource(
      value = Capability.Scope.class,
      names = {"TABLE", "VIEW", "TOPIC"})
  void testFailedSourceInventoryDoesNotFallBackToStoredAssets(Capability.Scope scope)
      throws Exception {
    Namespace namespace = mockAssetInventory(scope, "retained", "stale");
    IllegalStateException failure = new IllegalStateException("Source listing failed");
    switch (scope) {
      case TABLE:
        when(tableDispatcher.listTables(namespace)).thenThrow(failure);
        break;
      case VIEW:
        when(viewDispatcher.listViews(namespace)).thenThrow(failure);
        break;
      case TOPIC:
        when(topicDispatcher.listTopics(namespace)).thenThrow(failure);
        break;
      default:
        throw new AssertionError("Unexpected scope: " + scope);
    }

    MetalakeSnapshot snapshot = collectSnapshot();

    assertEquals(Collections.singleton(relationalCatalogName), snapshot.getFailedCatalogNames());
    assertFalse(snapshot.getAssetNodeById().containsKey(42L));
    assertFalse(snapshot.getAssetNodeById().containsKey(43L));
    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    assertMetric(metrics, "asset_count", null, MetricState.UNAVAILABLE);
    assertMetric(
        metrics,
        DashboardMetricNames.forCatalog(
            relationalCatalogName, relationalCatalogProvider, "asset_count"),
        null,
        MetricState.UNAVAILABLE);
  }

  @ParameterizedTest
  @EnumSource(
      value = Capability.Scope.class,
      names = {"TABLE", "VIEW", "TOPIC"})
  void testSourceInventoryPreservesCaseSensitiveNames(Capability.Scope scope) throws Exception {
    mockAssetInventory(scope, "MixedCase", "MIXEDCASE", "MixedCase", "MIXEDCASE");
    when(catalogWrapper.capabilities())
        .thenReturn(
            new Capability() {
              @Override
              public CapabilityResult caseSensitiveOnName(Scope requestedScope) {
                return requestedScope == scope
                    ? CapabilityResult.SUPPORTED
                    : CapabilityResult.unsupported("other scopes are case-insensitive");
              }
            });

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(2, snapshot.getSchemaNodes().iterator().next().getChildren().size());
    assertEquals("MixedCase", snapshot.getAssetNodeById().get(42L).getName());
    assertEquals("MIXEDCASE", snapshot.getAssetNodeById().get(43L).getName());
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdbc-oracle", "JDBC-Oracle", "JDBC-ORACLE"})
  void testOracleSourceInventoryPreservesDistinctPhysicalNames(String provider) throws Exception {
    mockAssetInventory(Capability.Scope.TABLE, "MyTable", "MYTABLE", "MyTable", "MYTABLE");
    mockOracleCapability(provider);
    mockAssetGovernance(Capability.Scope.TABLE);

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(2, snapshot.getTableNodes().size());
    assertEquals("MyTable", snapshot.getAssetNodeById().get(42L).getName());
    assertEquals("MYTABLE", snapshot.getAssetNodeById().get(43L).getName());
    assertEquals(
        "owner", snapshot.getAssetNodeById().get(42L).getOwners().iterator().next().name());
    assertEquals(Set.of(42L, 43L), snapshot.getTaggedObjectIds());
    assertEquals(
        Set.of("MyTable", "MYTABLE"),
        snapshot.getRoleIdToSecurableObjects().get(51L).stream()
            .map(SecurableObject::name)
            .collect(Collectors.toSet()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdbc-oracle", "JDBC-Oracle", "JDBC-ORACLE"})
  void testOracleSourceInventoryDoesNotMatchDifferentPhysicalName(String provider)
      throws Exception {
    mockAssetInventory(Capability.Scope.TABLE, "MYTABLE", "STALE", "MyTable");
    mockOracleCapability(provider);
    mockAssetGovernance(Capability.Scope.TABLE);

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(1, snapshot.getTableNodes().size());
    AssetNode table = snapshot.getTableNodes().iterator().next();
    assertEquals("MyTable", table.getName());
    assertEquals(-1L, table.getId());
    assertTrue(table.getOwners().isEmpty());
    assertFalse(snapshot.getAssetNodeById().containsKey(42L));
    assertFalse(snapshot.getAssetNodeById().containsKey(43L));
    assertFalse(snapshot.getAssetNodeById().containsKey(-1L));
    assertTrue(snapshot.getTaggedObjectIds().isEmpty());
    assertTrue(snapshot.getRoleIdToSecurableObjects().isEmpty());
    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    assertMetric(metrics, "asset_count", 1.0, MetricState.COMPLETE);
    assertMetric(metrics, "owned_asset_count", 0.0, MetricState.COMPLETE);
    assertMetric(metrics, "tagged_asset_count", 0.0, MetricState.COMPLETE);
    assertMetric(metrics, "policy_covered_asset_count", 0.0, MetricState.COMPLETE);
  }

  @Test
  void testMissingSourceSchemaIsDeletedAndHealthySchemaIsCounted() throws Exception {
    NameIdentifier stale = storedProbe();
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(false);

    MetalakeSnapshot snapshot = collectSnapshot();

    verify(store).delete(stale, Entity.EntityType.SCHEMA, true);
    verify(tableDispatcher, never())
        .listTables(
            Namespace.of(
                stale.namespace().levels()[0], stale.namespace().levels()[1], stale.name()));
    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(1, snapshot.getSchemaNodes().size());
    assertEquals(1, snapshot.getTableNodes().size());
    assertFalse(snapshot.getAssetNodeById().containsKey(99L));
    assertEquals(
        MetricsCollector.CollectionOutcome.COMPLETE,
        MetricsCollector.collectionOutcome(
            ImmutableMap.of(
                0L, new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz())));
  }

  @Test
  void testConcurrentMetadataCleanupIsHarmless() throws Exception {
    NameIdentifier stale = storedProbe();
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(false);
    when(store.delete(stale, Entity.EntityType.SCHEMA, true))
        .thenThrow(new NoSuchEntityException("already removed"));

    assertTrue(collectSnapshot().getFailedCatalogNames().isEmpty());
  }

  @Test
  void testManagedSchemaIsNeverReconciledAgainstExternalListing() throws Exception {
    NameIdentifier probe = storedProbe();
    Capability capability = mock(Capability.class);
    when(capability.managedStorage(any())).thenReturn(CapabilityResult.SUPPORTED);
    when(capability.supportsHierarchicalSchema())
        .thenReturn(CapabilityResult.unsupported("flat schema"));
    when(catalogWrapper.capabilities()).thenReturn(capability);

    MetalakeSnapshot snapshot = collectSnapshot();

    verify(schemaDispatcher, never()).listSchemas(any());
    verify(catalogWrapper, never()).doWithSchemaOps(any());
    verify(store, never()).delete(any(), any(), eq(true));
    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(probe, snapshot.getSchemaNodes().iterator().next().getNameIdent());
  }

  @Test
  void testSchemaOmittedFromListingButStillPresentIsRetained() throws Exception {
    NameIdentifier probe = storedProbe();
    Namespace ns = Namespace.of(metalakeName, relationalCatalogName, probe.name());
    when(tableDispatcher.listTables(ns)).thenReturn(new NameIdentifier[0]);
    when(viewDispatcher.listViews(ns)).thenReturn(new NameIdentifier[0]);

    MetalakeSnapshot snapshot = collectSnapshot();

    verify(store, never()).delete(any(), any(), eq(true));
    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(2, snapshot.getSchemaNodes().size());
    assertTrue(snapshot.getAssetNodeById().containsKey(99L));
  }

  @Test
  void testFailedExistenceProbePreservesMetadataAndNamesSchema() throws Exception {
    NameIdentifier probe = storedProbe();
    when(catalogWrapper.<Boolean>doWithSchemaOps(any()))
        .thenThrow(new IllegalStateException("secret connection password"));

    MetalakeSnapshot snapshot = collectSnapshot();

    verify(store, never()).delete(any(), any(), eq(true));
    assertEquals(probe, snapshot.getFailedSchemaIdentifiers().get(relationalCatalogName));
    List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
    MetricPO catalogMetric =
        metrics.stream()
            .filter(
                metric ->
                    metric
                        .getMetricName()
                        .equals(
                            "by_catalog::"
                                + relationalCatalogName
                                + "::jdbc-postgresql::asset_count"))
            .findFirst()
            .orElseThrow();
    assertEquals(MetricState.UNAVAILABLE, catalogMetric.getMetricState());
    assertTrue(catalogMetric.getMetricMessage().contains(probe.toString()));
    assertFalse(catalogMetric.getMetricMessage().contains("password"));
  }

  @Test
  void testSchemaListingFailureDoesNotDeleteMetadata() throws Exception {
    storedProbe();
    when(schemaDispatcher.listSchemas(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName)))
        .thenThrow(new IllegalStateException("source unavailable"));

    MetalakeSnapshot snapshot = collectSnapshot();

    verify(store, never()).delete(any(), any(), eq(true));
    verify(catalogWrapper, never()).doWithSchemaOps(any());
    assertTrue(snapshot.getFailedCatalogNames().contains(relationalCatalogName));
  }

  @Test
  void testMetadataCleanupFailureDoesNotReportCompleteCollection() throws Exception {
    NameIdentifier probe = storedProbe();
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(false);
    when(store.delete(probe, Entity.EntityType.SCHEMA, true))
        .thenThrow(new IOException("store down"));

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().contains(relationalCatalogName));
    assertEquals(probe, snapshot.getFailedSchemaIdentifiers().get(relationalCatalogName));
  }

  @Test
  void testCleanupLinkageErrorPreservesFailedSchemaIdentifier() throws Exception {
    NameIdentifier schemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, relationalSchemaName);
    when(tableDispatcher.listTables(
            NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName)))
        .thenThrow(new IllegalStateException("schema unavailable"));
    when(catalogWrapper.<Boolean>doWithSchemaOps(any()))
        .thenThrow(new NoClassDefFoundError("provider dependency"));

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().contains(relationalCatalogName));
    assertEquals(schemaIdent, snapshot.getFailedSchemaIdentifiers().get(relationalCatalogName));
  }

  @Test
  void testSchemaDroppedAfterListingDoesNotDiscardHealthySchema() throws Exception {
    NameIdentifier probe = storedProbe();
    when(schemaDispatcher.listSchemas(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName)))
        .thenReturn(
            new NameIdentifier[] {
              probe,
              NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, relationalSchemaName)
            });
    Namespace probeNamespace = Namespace.of(metalakeName, relationalCatalogName, probe.name());
    when(tableDispatcher.listTables(probeNamespace))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(probeNamespace, "old_table")});
    when(viewDispatcher.listViews(probeNamespace))
        .thenThrow(new IllegalStateException("Unknown database 'probe'"));
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(false);

    MetalakeSnapshot snapshot = collectSnapshot();

    verify(store).delete(probe, Entity.EntityType.SCHEMA, true);
    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertEquals(1, snapshot.getSchemaNodes().size());
    assertEquals(1, snapshot.getCatalogNodes().iterator().next().getChildren().size());
    assertEquals(1, snapshot.getTableNodes().size());
    assertFalse(snapshot.getAssetNodeById().containsKey(99L));
  }

  @Test
  void testHierarchicalSchemaRemovedWhileListingChildren() throws Exception {
    Capability capability = mock(Capability.class);
    when(capability.managedStorage(any())).thenReturn(CapabilityResult.unsupported("external"));
    when(capability.supportsHierarchicalSchema()).thenReturn(CapabilityResult.SUPPORTED);
    when(catalogWrapper.capabilities()).thenReturn(capability);
    Namespace root = NamespaceUtil.ofSchema(metalakeName, relationalCatalogName);
    NameIdentifier top = NameIdentifier.of(root, "top");
    when(schemaDispatcher.listSchemas(root)).thenReturn(new NameIdentifier[] {top});
    when(schemaDispatcher.listSchemas(Namespace.of(metalakeName, relationalCatalogName, "top")))
        .thenThrow(new IllegalStateException("schema disappeared"));
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(false);

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertTrue(snapshot.getSchemaNodes().isEmpty());
    verify(store).delete(top, Entity.EntityType.SCHEMA, true);
  }

  @Test
  void testMissingHierarchicalParentRemovesPreviouslyCollectedChildren() throws Exception {
    Capability capability = mock(Capability.class, Mockito.CALLS_REAL_METHODS);
    when(capability.managedStorage(any())).thenReturn(CapabilityResult.unsupported("external"));
    when(capability.supportsHierarchicalSchema()).thenReturn(CapabilityResult.SUPPORTED);
    when(catalogWrapper.capabilities()).thenReturn(capability);
    Namespace root = NamespaceUtil.ofSchema(metalakeName, relationalCatalogName);
    NameIdentifier top = NameIdentifier.of(root, "top");
    NameIdentifier child = NameIdentifier.of(root, "top:child");
    Namespace topNs = Namespace.of(metalakeName, relationalCatalogName, "top");
    Namespace childNs = Namespace.of(metalakeName, relationalCatalogName, "top:child");
    when(schemaDispatcher.listSchemas(root)).thenReturn(new NameIdentifier[] {top});
    when(schemaDispatcher.listSchemas(topNs)).thenReturn(new NameIdentifier[] {child});
    when(schemaDispatcher.listSchemas(childNs)).thenReturn(new NameIdentifier[0]);
    when(tableDispatcher.listTables(topNs)).thenThrow(new IllegalStateException("schema dropped"));
    when(tableDispatcher.listTables(childNs))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(childNs, "stale_table")});
    when(viewDispatcher.listViews(childNs)).thenReturn(new NameIdentifier[0]);
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(false);

    MetalakeSnapshot snapshot = collectSnapshot();

    assertTrue(snapshot.getFailedCatalogNames().isEmpty());
    assertTrue(snapshot.getSchemaNodes().isEmpty());
    assertTrue(snapshot.getTableNodes().isEmpty());
    assertTrue(snapshot.getCatalogNodes().iterator().next().getChildren().isEmpty());
  }

  @Test
  void testHierarchicalSchemasUseTheirActualParents() throws Exception {
    Capability capability = mock(Capability.class);
    when(capability.managedStorage(any()))
        .thenReturn(CapabilityResult.unsupported("test external catalog"));
    when(capability.supportsHierarchicalSchema()).thenReturn(CapabilityResult.SUPPORTED);
    when(catalogWrapper.capabilities()).thenReturn(capability);

    Namespace catalogNamespace = NamespaceUtil.ofSchema(metalakeName, relationalCatalogName);
    Namespace topNamespace = Namespace.of(metalakeName, relationalCatalogName, "top");
    Namespace childNamespace = Namespace.of(metalakeName, relationalCatalogName, "top:child");
    when(schemaDispatcher.listSchemas(catalogNamespace))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, "top")
            });
    when(schemaDispatcher.listSchemas(topNamespace))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, "top:child")
            });
    when(schemaDispatcher.listSchemas(childNamespace)).thenReturn(new NameIdentifier[0]);
    when(tableDispatcher.listTables(topNamespace)).thenReturn(new NameIdentifier[0]);
    when(tableDispatcher.listTables(childNamespace)).thenReturn(new NameIdentifier[0]);
    when(viewDispatcher.listViews(topNamespace)).thenReturn(new NameIdentifier[0]);
    when(viewDispatcher.listViews(childNamespace)).thenReturn(new NameIdentifier[0]);

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake());

      AssetNode catalog = snapshot.getCatalogNodes().iterator().next();
      assertEquals(1, catalog.getChildren().size());
      AssetNode top = catalog.getChildren().iterator().next();
      assertEquals("top", top.getName());
      assertEquals(1, top.getChildren().size());
      AssetNode child = top.getChildren().iterator().next();
      assertEquals("top:child", child.getName());
      assertEquals(top.getNameIdent(), child.getParentIdent());
      assertEquals(
          NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, "top:child"),
          child.getNameIdent());

      List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
      assertMetric(
          metrics,
          DirectChildCountMetricNames.forCatalog(relationalCatalogName),
          1.0,
          MetricState.COMPLETE);
      assertMetric(
          metrics,
          DirectChildCountMetricNames.forSchema(relationalCatalogName, "top"),
          1.0,
          MetricState.COMPLETE);
      assertMetric(
          metrics,
          DirectChildCountMetricNames.forSchema(relationalCatalogName, "top:child"),
          0.0,
          MetricState.COMPLETE);
    }
  }

  @Test
  void testExternalSchemaNotInStoreUsesDispatcherAndSkipsFunctions() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);

      // Mock store.list() for tables to throw NoSuchEntityException (schema not in store)
      when(store.list(
              eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName)),
              eq(TableEntity.class),
              eq(Entity.EntityType.TABLE)))
          .thenThrow(new NoSuchEntityException("No such schema entity: %s", relationalSchemaName));

      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      BaseMetalake metalake =
          BaseMetalake.builder()
              .withId(mockId)
              .withName(metalakeName)
              .withVersion(SchemaVersion.V_0_1)
              .withAuditInfo(
                  AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
              .build();

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake);
      assertNotNull(snapshot);
      assertEquals(metalakeName, snapshot.getAssetTreeRoot().getName());
      assertTrue(snapshot.getFailedCatalogNames().isEmpty());
      assertEquals(1, snapshot.getSchemaNodes().size());
      assertEquals(-1L, snapshot.getSchemaNodes().iterator().next().getId());
      assertEquals(1, snapshot.getTableNodes().size());
      assertEquals(tableName, snapshot.getTableNodes().iterator().next().getName());
      assertEquals(1, snapshot.getViewNodes().size());
      assertTrue(snapshot.getFunctionNodes().isEmpty());
      verify(store, never())
          .list(
              NamespaceUtil.ofFunction(metalakeName, relationalCatalogName, relationalSchemaName),
              FunctionEntity.class,
              Entity.EntityType.FUNCTION);

      List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
      assertMetric(metrics, "asset_count", 2.0, MetricState.COMPLETE);
      assertMetric(
          metrics,
          DashboardMetricNames.forCatalog(
              relationalCatalogName, relationalCatalogProvider, "asset_count"),
          2.0,
          MetricState.COMPLETE);
      assertMetric(
          metrics,
          DashboardMetricNames.forCatalog(relationalCatalogName, "schema_count"),
          1.0,
          MetricState.COMPLETE);
      assertMetric(
          metrics,
          DashboardMetricNames.forCatalog(relationalCatalogName, "table_count"),
          1.0,
          MetricState.COMPLETE);
      assertMetric(
          metrics,
          DashboardMetricNames.forCatalog(relationalCatalogName, "function_count"),
          0.0,
          MetricState.COMPLETE);
    }
  }

  @Test
  void testUnsupportedViewListingDoesNotFailRelationalCatalog() throws Exception {
    when(catalogWrapper.<Boolean>doWithViewOps(any()))
        .thenThrow(new UnsupportedOperationException("Catalog does not support view operations"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      assertEquals(
          MetricsCollector.CollectionOutcome.COMPLETE,
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 1_500L));

      MetalakeSnapshot snapshot = collector.getMetalakeSnapshots().get(metalakeName);
      assertTrue(snapshot.getFailedCatalogNames().isEmpty());
      assertEquals(1, snapshot.getTableNodes().size());
      assertEquals(0, snapshot.getViewNodes().size());
      verify(viewDispatcher, never()).listViews(any());

      List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
      assertMetric(metrics, "asset_count", 1.0, MetricState.COMPLETE);
      assertMetric(
          metrics,
          "by_catalog::"
              + relationalCatalogName
              + "::"
              + relationalCatalogProvider
              + "::asset_count",
          1.0,
          MetricState.COMPLETE);
      assertMetric(metrics, "by_asset_type::TABLE::asset_count", 1.0, MetricState.COMPLETE);
      assertMetric(metrics, "by_asset_type::VIEW::asset_count", 0.0, MetricState.COMPLETE);
    }
  }

  @Test
  void testDisabledCatalogIsExcludedWithoutLoadingItsDispatcher() throws Exception {
    CatalogEntity disabledCatalog =
        CatalogEntity.builder()
            .withId(2L)
            .withName("disabled_catalog")
            .withNamespace(NamespaceUtil.ofCatalog(metalakeName))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of(Catalog.PROPERTY_IN_USE, "false"))
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofCatalog(metalakeName), CatalogEntity.class, Entity.EntityType.CATALOG))
        .thenReturn(ImmutableList.of(disabledCatalog));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      clearInvocations(catalogManager);

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake());

      assertEquals(0, snapshot.getCatalogNodes().size());
      verify(catalogManager, never()).doWithCatalogWrapper(any(), any());
    }
  }

  @Test
  void testPublishModesWriteCurrentAndHistoryAsConfigured() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      BaseMetalake metalake = metalake();

      collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      verify(metricDataService).replaceCurrentMetrics(eq(mockId), anyMap(), eq(1_000L));
      verify(metricDataService, never())
          .replaceCurrentAndAppendHistory(eq(mockId), anyMap(), eq(1_000L));

      collector.collectAndPublish(
          metalake, MetricsCollector.PublishMode.CURRENT_AND_HISTORY, 2_000L);
      verify(metricDataService).replaceCurrentAndAppendHistory(eq(mockId), anyMap(), eq(2_000L));
    }
  }

  @Test
  void testAuthorizationPreloadRunsOnlyForSchemaFailures() throws Exception {
    when(serverConfig.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(true);
    try (MockedStatic<MetricDataService> mockedService =
            Mockito.mockStatic(MetricDataService.class);
        MockedConstruction<MemoizedJcasbinAuthorizer> authorizers =
            Mockito.mockConstruction(MemoizedJcasbinAuthorizer.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      collector.collectAndPublish(metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);

      verify(authorizers.constructed().get(0), never()).preloadSnapshotPolicies(any());
      when(tableDispatcher.listTables(
              eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
          .thenThrow(new RuntimeException("catalog unavailable"));

      collector.collectAndPublish(metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L);

      assertEquals(2, authorizers.constructed().size());
      verify(authorizers.constructed().get(1)).preloadSnapshotPolicies(metalakeName);
    }
  }

  @Test
  void testStartupCreatesPendingMarkerBeforePublishingAndClearsItOnSuccess() throws Exception {
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenReturn(ImmutableList.of(metalake()));
    MetricDirtyPO dirty = mock(MetricDirtyPO.class);
    when(dirty.getRevision()).thenReturn(7L);
    when(metricDataService.getDirtyMetalake(mockId)).thenReturn(null, dirty);

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      assertFalse(collector.isStartupCollectionComplete());

      collector.collectStartupMetrics(1_250L);
      assertTrue(collector.isStartupCollectionComplete());

      InOrder order = inOrder(metricDataService);
      order.verify(metricDataService).markMetalakeDirty(mockId, 1_250L);
      order.verify(metricDataService).replaceCurrentMetrics(eq(mockId), anyMap(), eq(1_250L));
      order.verify(metricDataService).deleteDirtyIfRevision(mockId, 7L);
      verify(metricDataService, never())
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(1_250L), eq(1_250L));
    }
  }

  @Test
  void testStartupReleasesEachMetalakeAfterPublication() throws Exception {
    BaseMetalake second = mock(BaseMetalake.class);
    when(second.id()).thenReturn(2L);
    when(second.name()).thenReturn("second_metalake");
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenReturn(ImmutableList.of(metalake(), second));
    MetricDirtyPO dirty = mock(MetricDirtyPO.class);
    when(dirty.getRevision()).thenReturn(7L);
    when(metricDataService.getDirtyMetalake(anyLong())).thenReturn(dirty);

    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      ExecutorService executor = mock(ExecutorService.class);
      setCalculationExecutor(collector, executor);
      doAnswer(
              invocation -> {
                assertTrue(collector.areStartupMetalakesRegistered());
                assertTrue(collector.isStartupCollectionPending(mockId));
                assertTrue(collector.isStartupCollectionPending(2L));
                invocation.<Runnable>getArgument(0).run();
                return null;
              })
          .doAnswer(
              invocation -> {
                assertFalse(collector.isStartupCollectionPending(mockId));
                assertTrue(collector.isStartupCollectionPending(2L));
                assertFalse(collector.isStartupCollectionComplete());
                invocation.<Runnable>getArgument(0).run();
                return null;
              })
          .when(executor)
          .execute(any());
      doAnswer(
              invocation -> {
                assertTrue(collector.isStartupCollectionPending(mockId));
                return null;
              })
          .when(metricDataService)
          .replaceCurrentMetrics(eq(mockId), anyMap(), anyLong());
      when(metricDataService.deleteDirtyIfRevision(mockId, 7L))
          .thenAnswer(
              invocation -> {
                assertTrue(collector.isStartupCollectionPending(mockId));
                return true;
              });

      collector.collectStartupMetrics(1_250L);

      assertTrue(collector.isStartupCollectionComplete());
      verify(metricDataService).replaceCurrentMetrics(eq(mockId), anyMap(), eq(1_250L));
      verify(metricDataService).deleteDirtyIfRevision(mockId, 7L);
    }
  }

  @Test
  void testStartupSubmissionFailureRetainsAcceptedTaskGate() throws Exception {
    BaseMetalake second = mock(BaseMetalake.class);
    when(second.id()).thenReturn(2L);
    when(second.name()).thenReturn("second_metalake");
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenReturn(ImmutableList.of(metalake(), second));
    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      ExecutorService executor = mock(ExecutorService.class);
      setCalculationExecutor(collector, executor);
      List<Runnable> accepted = new ArrayList<>();
      doAnswer(
              invocation -> {
                accepted.add(invocation.getArgument(0));
                return null;
              })
          .doThrow(new RejectedExecutionException("executor stopped"))
          .when(executor)
          .execute(any());

      collector.collectStartupMetrics(1_250L);

      assertTrue(collector.areStartupMetalakesRegistered());
      assertTrue(collector.isStartupCollectionPending(mockId));
      assertFalse(collector.isStartupCollectionPending(2L));
      assertFalse(collector.isStartupCollectionComplete());
      assertEquals(1, accepted.size());
      accepted.get(0).run();
      assertTrue(collector.isStartupCollectionComplete());
    }
  }

  @Test
  void testIncompleteStartupKeepsPendingMarkerForRetry() throws Exception {
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenReturn(ImmutableList.of(metalake()));
    MetricDirtyPO dirty = mock(MetricDirtyPO.class);
    when(dirty.getRevision()).thenReturn(8L);
    when(metricDataService.getDirtyMetalake(mockId)).thenReturn(null, dirty);
    when(tableDispatcher.listTables(
            eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
        .thenThrow(new RuntimeException("catalog unavailable"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      assertFalse(collector.isStartupCollectionComplete());

      collector.collectStartupMetrics(1_300L);
      assertTrue(collector.isStartupCollectionComplete());

      InOrder order = inOrder(metricDataService);
      order.verify(metricDataService).markMetalakeDirty(mockId, 1_300L);
      order.verify(metricDataService).replaceCurrentMetrics(eq(mockId), anyMap(), eq(1_300L));
      verify(metricDataService, never()).deleteDirtyIfRevision(anyLong(), anyLong());
      verify(metricDataService, never())
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(1_300L), eq(1_300L));
    }
  }

  @Test
  void testAbortedStartupRetainsDirtyMarkerAndPropagatesFailure() throws Exception {
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenReturn(ImmutableList.of(metalake()));
    MetricDirtyPO dirty = mock(MetricDirtyPO.class);
    when(dirty.getRevision()).thenReturn(8L);
    when(metricDataService.getDirtyMetalake(mockId)).thenReturn(dirty);

    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      ExecutorService executor = mock(ExecutorService.class);
      setCalculationExecutor(collector, executor);
      doAnswer(
              invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
              })
          .when(executor)
          .execute(any());
      for (Throwable failure :
          new Throwable[] {
            new ServiceConfigurationError("provider", new OutOfMemoryError("test")),
            new RuntimeException(new InterruptedException("stopping")),
            new CancellationException("cancelled")
          }) {
        doThrow(failure)
            .when(catalogManager)
            .doWithCatalogWrapper(
                eq(NameIdentifierUtil.ofCatalog(metalakeName, relationalCatalogName)), any());
        try {
          Class<? extends Throwable> expected =
              failure instanceof ServiceConfigurationError
                  ? OutOfMemoryError.class
                  : CancellationException.class;
          assertThrows(expected, () -> collector.collectStartupMetrics(1_300L));
          assertFalse(collector.isStartupCollectionPending(mockId));
          verify(metricDataService, never()).deleteDirtyIfRevision(anyLong(), anyLong());
          verify(metricDataService, never()).replaceCurrentMetrics(anyLong(), anyMap(), anyLong());
          verify(metricDataService, never())
              .replaceCurrentMetricsAndMarkDirty(anyLong(), anyMap(), anyLong(), anyLong());
        } finally {
          Thread.interrupted();
        }
      }
    }
  }

  @Test
  void testStartupGateOpensWhenMetalakeListingFails() throws Exception {
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenThrow(new RuntimeException("list failed"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      assertFalse(collector.isStartupCollectionComplete());

      collector.collectStartupMetrics(1_350L);

      assertTrue(collector.isStartupCollectionComplete());
      verify(metricDataService, never()).markMetalakeDirty(anyLong(), anyLong());
    }
  }

  @Test
  void testDailyCancellationDoesNotSuppressNextCollection() throws Exception {
    when(store.list(Namespace.empty(), BaseMetalake.class, Entity.EntityType.METALAKE))
        .thenThrow(new CancellationException("cancelled"))
        .thenReturn(Collections.emptyList());

    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      collector.collectThenCleanMetrics();
      verify(metricDataService, never()).cleanMetricsByTimestamp(anyLong());
      verify(metricDataService, never()).cleanInvalidMetrics();

      collector.collectThenCleanMetrics();
      verify(metricDataService).cleanMetricsByTimestamp(anyLong());
      verify(metricDataService).cleanInvalidMetrics();
    }
  }

  @Test
  void testFailedPublishRestoresPreviousSnapshot() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      BaseMetalake metalake = metalake();

      collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      MetalakeSnapshot previous = collector.getMetalakeSnapshots().get(metalakeName);
      doThrow(new RuntimeException("write failed"))
          .when(metricDataService)
          .replaceCurrentMetrics(eq(mockId), anyMap(), eq(2_000L));

      assertThrows(
          RuntimeException.class,
          () ->
              collector.collectAndPublish(
                  metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L));
      assertSame(previous, collector.getMetalakeSnapshots().get(metalakeName));
    }
  }

  @Test
  void testPublishErrorRestoresPreviousSnapshot() throws Exception {
    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      collector.collectAndPublish(metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      MetalakeSnapshot previous = collector.getMetalakeSnapshots().get(metalakeName);
      Error failure = new AssertionError("publication failed");
      doThrow(failure)
          .when(metricDataService)
          .replaceCurrentMetrics(eq(mockId), anyMap(), eq(2_000L));

      assertSame(
          failure,
          assertThrows(
              AssertionError.class,
              () ->
                  collector.collectAndPublish(
                      metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L)));
      assertSame(previous, collector.getMetalakeSnapshots().get(metalakeName));
      verify(metricDataService, never())
          .replaceCurrentMetricsAndMarkDirty(anyLong(), anyMap(), anyLong(), anyLong());
    }
  }

  @Test
  void testCatalogFailurePublishesIncompleteCurrentAndCreatesDirtyMarker() throws Exception {
    when(catalogWrapper.<Boolean>doWithViewOps(any()))
        .thenThrow(new UnsupportedOperationException("Catalog does not support view operations"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      BaseMetalake metalake = metalake();

      collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      when(tableDispatcher.listTables(
              eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
          .thenThrow(new RuntimeException("catalog unavailable"));

      MetricsCollector.CollectionOutcome outcome =
          collector.collectAndPublish(metalake, MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L);

      assertEquals(MetricsCollector.CollectionOutcome.INCOMPLETE, outcome);
      verify(metricDataService)
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(2_000L), eq(2_000L));
      MetalakeSnapshot snapshot = collector.getMetalakeSnapshots().get(metalakeName);
      assertTrue(snapshot.getFailedCatalogNames().contains(relationalCatalogName));
      assertEquals(
          Boolean.FALSE, snapshot.getViewListingSupportByCatalog().get(relationalCatalogName));

      List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
      assertMetric(metrics, "by_asset_type::VIEW::asset_count", 0.0, MetricState.COMPLETE);
    }
  }

  @Test
  void testNoClassDefFoundErrorIsIsolatedToCatalog() throws Exception {
    assertCatalogFailureIsIsolated(new NoClassDefFoundError("provider dependency"));
  }

  @Test
  void testServiceConfigurationErrorIsIsolatedToCatalog() throws Exception {
    assertCatalogFailureIsIsolated(new ServiceConfigurationError("provider configuration"));
  }

  @Test
  void testAssertionErrorIsIsolatedToCatalog() throws Exception {
    assertCatalogFailureIsIsolated(new AssertionError("provider assertion"));
  }

  @Test
  void testRuntimeExceptionIsIsolatedToCatalog() throws Exception {
    assertCatalogFailureIsIsolated(new IllegalStateException("provider unavailable"));
  }

  @Test
  void testLateCatalogErrorDiscardsPartialTreeAndIndexes() throws Exception {
    mockStoredRelationalSchema(41L);
    when(store.list(
            NamespaceUtil.ofFunction(metalakeName, relationalCatalogName, relationalSchemaName),
            FunctionEntity.class,
            Entity.EntityType.FUNCTION))
        .thenThrow(new AssertionError("provider failed after collecting tables and views"));

    MetalakeSnapshot snapshot = collectSnapshot();

    assertEquals(Collections.singleton(relationalCatalogName), snapshot.getFailedCatalogNames());
    assertEquals(
        NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, relationalSchemaName),
        snapshot.getFailedSchemaIdentifiers().get(relationalCatalogName));
    assertTrue(snapshot.getSchemaNodes().isEmpty());
    assertTrue(snapshot.getTableNodes().isEmpty());
    assertTrue(snapshot.getViewNodes().isEmpty());
    assertFalse(snapshot.getAssetNodeById().containsKey(41L));
    assertTrue(snapshot.getCatalogNodes().iterator().next().getChildren().isEmpty());
    assertEquals(2, snapshot.getAssetNodeByIdent().size());
  }

  @Test
  void testWrappedFatalErrorAbortsWithoutPublishing() throws Exception {
    Error fatal = new OutOfMemoryError("test");
    assertSame(
        fatal,
        assertCollectionAborted(
            new ServiceConfigurationError("provider", fatal), fatal.getClass()));
  }

  @Test
  void testWrappedInterruptionAbortsWithoutPublishingOrSchemaCleanup() throws Exception {
    try {
      assertCollectionAborted(
          new RuntimeException(new InterruptedException("stopping")), CancellationException.class);
      assertTrue(Thread.currentThread().isInterrupted());
      verify(catalogWrapper, never()).doWithSchemaOps(any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void testCancellationAbortsWithoutPublishing() throws Exception {
    CancellationException cancelled = new CancellationException("cancelled");
    assertSame(cancelled, assertCollectionAborted(cancelled, CancellationException.class));
  }

  @Test
  void testFailedCatalogWithNoMetricRecipientsCompletesWithoutDirtyMarker() throws Exception {
    when(serverConfig.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(true);
    when(catalogManager.doWithCatalogWrapper(
            eq(NameIdentifierUtil.ofCatalog(metalakeName, relationalCatalogName)), any()))
        .thenThrow(new NoClassDefFoundError("provider dependency"));
    try (MockedStatic<MetricDataService> mockedService =
            Mockito.mockStatic(MetricDataService.class);
        MockedConstruction<MemoizedJcasbinAuthorizer> ignored =
            Mockito.mockConstruction(MemoizedJcasbinAuthorizer.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      assertEquals(
          MetricsCollector.CollectionOutcome.COMPLETE,
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 5_000L));
      verify(metricDataService).replaceCurrentMetrics(mockId, Collections.emptyMap(), 5_000L);
    }
  }

  @Test
  void testFailedCustomProviderIsPreservedInUnavailableMetricName() throws Exception {
    String customCatalogName = "custom_catalog";
    String customProvider = "vendor-custom";
    CatalogEntity customCatalog =
        CatalogEntity.builder()
            .withId(2L)
            .withName(customCatalogName)
            .withNamespace(NamespaceUtil.ofCatalog(metalakeName))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider(customProvider)
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofCatalog(metalakeName), CatalogEntity.class, Entity.EntityType.CATALOG))
        .thenReturn(ImmutableList.of(customCatalog));
    when(catalogManager.doWithCatalogWrapper(
            eq(NameIdentifierUtil.ofCatalog(metalakeName, customCatalogName)), any()))
        .thenThrow(new RuntimeException("custom provider unavailable"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      MetalakeSnapshot snapshot = collector.loadAllDataForMetalake(metalake());

      assertEquals(customProvider, snapshot.getCatalogProviders().get(customCatalogName));
      assertTrue(snapshot.getFailedCatalogNames().contains(customCatalogName));
      List<MetricPO> metrics = new MetricsCalculator(snapshot).calculateMetricsForDisableAuthz();
      assertMetric(
          metrics,
          "by_catalog::custom_catalog::vendor-custom::asset_count",
          null,
          MetricState.UNAVAILABLE);
    }
  }

  @Test
  void testCollectionOutcomeUsesPublishedMetricStates() {
    MetricPO completeMetric = mock(MetricPO.class);
    when(completeMetric.getMetricState()).thenReturn(MetricState.COMPLETE);
    MetricPO partialMetric = mock(MetricPO.class);
    when(partialMetric.getMetricState()).thenReturn(MetricState.PARTIAL);

    assertEquals(
        MetricsCollector.CollectionOutcome.COMPLETE,
        MetricsCollector.collectionOutcome(ImmutableMap.of(1L, ImmutableList.of(completeMetric))));
    assertEquals(
        MetricsCollector.CollectionOutcome.INCOMPLETE,
        MetricsCollector.collectionOutcome(
            ImmutableMap.of(1L, ImmutableList.of(completeMetric, partialMetric))));
  }

  @Test
  void testCatalogStorageExceptionsPublishIncompleteAndCreateDirtyMarker() throws Exception {
    when(store.list(
            NamespaceUtil.ofSchema(metalakeName, relationalCatalogName),
            SchemaEntity.class,
            Entity.EntityType.SCHEMA))
        .thenThrow(new IOException("catalog storage unavailable"))
        .thenThrow(new RuntimeException("catalog storage failed unexpectedly"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      assertEquals(
          MetricsCollector.CollectionOutcome.INCOMPLETE,
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_100L));
      verify(metricDataService)
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(2_100L), eq(2_100L));
      assertTrue(
          collector
              .getMetalakeSnapshots()
              .get(metalakeName)
              .getFailedCatalogNames()
              .contains(relationalCatalogName));

      assertEquals(
          MetricsCollector.CollectionOutcome.INCOMPLETE,
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_200L));
      verify(metricDataService)
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(2_200L), eq(2_200L));
      assertTrue(
          collector
              .getMetalakeSnapshots()
              .get(metalakeName)
              .getFailedCatalogNames()
              .contains(relationalCatalogName));
    }
  }

  @Test
  void testRecoveredCatalogReturnsCompleteOnIncrementalRetry() throws Exception {
    NameIdentifier tableIdent =
        NameIdentifierUtil.ofTable(
            metalakeName, relationalCatalogName, relationalSchemaName, tableName);
    when(tableDispatcher.listTables(
            eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
        .thenThrow(new RuntimeException("catalog unavailable"))
        .thenReturn(new NameIdentifier[] {tableIdent});

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      assertEquals(
          MetricsCollector.CollectionOutcome.INCOMPLETE,
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_500L));
      assertEquals(
          MetricsCollector.CollectionOutcome.COMPLETE,
          collector.refreshAndPublishDirtyMetalake(
              metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_600L));

      verify(metricDataService)
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(2_500L), eq(2_500L));
      verify(metricDataService).replaceCurrentMetrics(eq(mockId), anyMap(), eq(2_600L));
      verify(metricDataService, never())
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(2_600L), eq(2_600L));
    }
  }

  @Test
  void testDailyIncompletePublishAppendsHistoryAndCreatesDirtyMarker() throws Exception {
    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      when(viewDispatcher.listViews(
              eq(NamespaceUtil.ofView(metalakeName, relationalCatalogName, relationalSchemaName))))
          .thenThrow(new RuntimeException("catalog unavailable"));

      MetricsCollector.CollectionOutcome outcome =
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_AND_HISTORY, 3_000L);

      assertEquals(MetricsCollector.CollectionOutcome.INCOMPLETE, outcome);
      verify(metricDataService)
          .replaceCurrentAndAppendHistoryAndMarkDirty(eq(mockId), anyMap(), eq(3_000L), eq(3_000L));
    }
  }

  @Test
  void testSharedStorageFailureIsPropagatedWithoutPublishingPartialMetrics() throws Exception {
    when(store.list(NamespaceUtil.ofUser(metalakeName), UserEntity.class, Entity.EntityType.USER))
        .thenThrow(new IOException("database unavailable"));

    try (MockedStatic<MetricDataService> mockedMetricDataService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedMetricDataService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      assertThrows(
          IOException.class,
          () ->
              collector.collectAndPublish(
                  metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 4_000L));
      verify(metricDataService, never()).replaceCurrentMetrics(eq(mockId), anyMap(), eq(4_000L));
      verify(metricDataService, never())
          .replaceCurrentMetricsAndMarkDirty(eq(mockId), anyMap(), eq(4_000L), eq(4_000L));
    }
  }

  private void mockListCatalogFromStore() throws IOException {
    mockListCatalogFromStore(Catalog.Type.RELATIONAL);
  }

  private void mockListCatalogFromStore(Catalog.Type catalogType) throws IOException {
    mockListCatalogFromStore(catalogType, relationalCatalogProvider);
  }

  private void mockListCatalogFromStore(Catalog.Type catalogType, String provider)
      throws IOException {
    CatalogEntity catalogEntity =
        CatalogEntity.builder()
            .withId(1L)
            .withName(relationalCatalogName)
            .withNamespace(NamespaceUtil.ofCatalog(metalakeName))
            .withType(catalogType)
            .withProvider(provider)
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofCatalog(metalakeName), CatalogEntity.class, Entity.EntityType.CATALOG))
        .thenReturn(ImmutableList.of(catalogEntity));
  }

  private Namespace mockAssetInventory(
      Capability.Scope scope,
      String firstStoredName,
      String secondStoredName,
      String... sourceNames)
      throws IOException {
    mockListCatalogFromStore(
        scope == Capability.Scope.TOPIC ? Catalog.Type.MESSAGING : Catalog.Type.RELATIONAL);
    mockStoredRelationalSchema(41L);
    Namespace namespace = Namespace.of(metalakeName, relationalCatalogName, relationalSchemaName);
    NameIdentifier[] identifiers = new NameIdentifier[sourceNames.length];
    for (int i = 0; i < sourceNames.length; i++) {
      identifiers[i] = NameIdentifier.of(namespace, sourceNames[i]);
    }
    when(tableDispatcher.listTables(namespace)).thenReturn(new NameIdentifier[0]);
    when(viewDispatcher.listViews(namespace)).thenReturn(new NameIdentifier[0]);
    switch (scope) {
      case TABLE:
        mockStoredAssets(
            namespace,
            TableEntity.class,
            Entity.EntityType.TABLE,
            firstStoredName,
            secondStoredName);
        when(tableDispatcher.listTables(namespace)).thenReturn(identifiers);
        break;
      case VIEW:
        mockStoredAssets(
            namespace, ViewEntity.class, Entity.EntityType.VIEW, firstStoredName, secondStoredName);
        when(viewDispatcher.listViews(namespace)).thenReturn(identifiers);
        break;
      case TOPIC:
        mockStoredAssets(
            namespace,
            TopicEntity.class,
            Entity.EntityType.TOPIC,
            firstStoredName,
            secondStoredName);
        when(topicDispatcher.listTopics(namespace)).thenReturn(identifiers);
        break;
      default:
        throw new AssertionError("Unexpected scope: " + scope);
    }
    return namespace;
  }

  private <E extends Entity & HasIdentifier> void mockStoredAssets(
      Namespace namespace,
      Class<E> entityClass,
      Entity.EntityType entityType,
      String firstName,
      String secondName)
      throws IOException {
    E first = mock(entityClass);
    when(first.id()).thenReturn(42L);
    when(first.name()).thenReturn(firstName);
    E second = mock(entityClass);
    when(second.id()).thenReturn(43L);
    when(second.name()).thenReturn(secondName);
    when(store.list(namespace, entityClass, entityType))
        .thenReturn(ImmutableList.of(first, second));
  }

  private void mockAssetGovernance(Capability.Scope scope) throws IOException {
    List<OwnerNameRelPO> owners = new ArrayList<>();
    List<TagNameMetadataObjectRelPO> tags = new ArrayList<>();
    List<SecurableObjectPO> securableObjects = new ArrayList<>();
    for (long id : new long[] {42L, 43L}) {
      owners.add(
          OwnerNameRelPO.builder()
              .withOwnerId("owner")
              .withOwnerType("USER")
              .withMetadataObjectId(id)
              .withMetadataObjectType(scope.name())
              .build());
      tags.add(
          TagNameMetadataObjectRelPO.builder()
              .withObjectId(id)
              .withTagName("classification")
              .build());
      securableObjects.add(
          SecurableObjectPO.builder()
              .withRoleId(51L)
              .withMetadataObjectId(id)
              .withType(scope.name())
              .withPrivilegeNames("[\"MANAGE_GRANTS\"]")
              .withPrivilegeConditions("[\"ALLOW\"]")
              .withCurrentVersion(1L)
              .withLastVersion(1L)
              .withDeletedAt(0L)
              .build());
    }
    when(metricDataService.listOwnerNameRelsByMetalakeId(mockId)).thenReturn(owners);
    when(metricDataService.listTagNameMetadataObjectRelsByMetalakeId(mockId)).thenReturn(tags);
    when(metricDataService.listEnabledPolicyMetadataObjectIdsByMetalakeId(mockId))
        .thenReturn(Set.of(42L, 43L));
    RoleEntity role = mock(RoleEntity.class);
    when(role.id()).thenReturn(51L);
    when(store.list(NamespaceUtil.ofRole(metalakeName), RoleEntity.class, Entity.EntityType.ROLE))
        .thenReturn(Collections.singletonList(role));
    when(metricDataService.listSecurableObjectsByRoleIds(Collections.singleton(51L)))
        .thenReturn(securableObjects);
  }

  private void mockOracleCapability(String provider) throws Exception {
    mockListCatalogFromStore(Catalog.Type.RELATIONAL, provider);
    when(catalogWrapper.capabilities())
        .thenReturn(
            new Capability() {
              @Override
              public String normalizeName(Scope scope, String name) {
                // Match Oracle's input contract without adding a catalog dependency to metrics.
                // Keep this folding behavior in sync with OracleIdentifierUtil.toPhysicalName.
                return name.startsWith("\"") && name.endsWith("\"")
                    ? name.substring(1, name.length() - 1)
                    : name.toUpperCase(Locale.ROOT);
              }
            });
  }

  private void assertCatalogFailureIsIsolated(Throwable failure) throws Exception {
    CatalogEntity failed =
        CatalogEntity.builder()
            .withId(2L)
            .withName("failed_catalog")
            .withNamespace(NamespaceUtil.ofCatalog(metalakeName))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("vendor-custom")
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    CatalogEntity healthy =
        store
            .list(
                NamespaceUtil.ofCatalog(metalakeName),
                CatalogEntity.class,
                Entity.EntityType.CATALOG)
            .get(0);
    when(store.list(
            NamespaceUtil.ofCatalog(metalakeName), CatalogEntity.class, Entity.EntityType.CATALOG))
        .thenReturn(ImmutableList.of(failed, healthy));
    when(catalogManager.doWithCatalogWrapper(eq(failed.nameIdentifier()), any()))
        .thenThrow(failure);

    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);

      assertEquals(
          MetricsCollector.CollectionOutcome.INCOMPLETE,
          collector.collectAndPublish(
              metalake(), MetricsCollector.PublishMode.CURRENT_AND_HISTORY, 5_000L));
      ArgumentCaptor<Map<Long, List<MetricPO>>> published = ArgumentCaptor.forClass(Map.class);
      verify(metricDataService)
          .replaceCurrentAndAppendHistoryAndMarkDirty(
              eq(mockId), published.capture(), eq(5_000L), eq(5_000L));
      List<MetricPO> metrics =
          published.getValue().get(MetricsCollector.MOCK_USER_ID_FOR_DISABLE_AUTHZ);
      assertMetric(
          metrics,
          "by_catalog::failed_catalog::vendor-custom::asset_count",
          null,
          MetricState.UNAVAILABLE);
      assertMetric(
          metrics,
          "by_catalog::rel_catalog::jdbc-postgresql::asset_count",
          2.0,
          MetricState.COMPLETE);
      assertMetric(metrics, "asset_count", 2.0, MetricState.PARTIAL);
      MetalakeSnapshot snapshot = collector.getMetalakeSnapshots().get(metalakeName);
      assertEquals(Collections.singleton(failed.name()), snapshot.getFailedCatalogNames());
      assertEquals(1, snapshot.getTableNodes().size());
      assertTrue(snapshot.getAssetNodeById().get(failed.id()).getChildren().isEmpty());
    }
  }

  private <T extends Throwable> T assertCollectionAborted(Throwable failure, Class<T> expected)
      throws Exception {
    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      collector.collectAndPublish(metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 1_000L);
      MetalakeSnapshot previous = collector.getMetalakeSnapshots().get(metalakeName);
      clearInvocations(metricDataService, catalogWrapper);
      when(tableDispatcher.listTables(
              NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName)))
          .thenThrow(failure);

      T thrown =
          assertThrows(
              expected,
              () ->
                  collector.collectAndPublish(
                      metalake(), MetricsCollector.PublishMode.CURRENT_ONLY, 2_000L));
      assertSame(previous, collector.getMetalakeSnapshots().get(metalakeName));
      verify(metricDataService, never()).replaceCurrentMetrics(anyLong(), anyMap(), anyLong());
      verify(metricDataService, never())
          .replaceCurrentMetricsAndMarkDirty(anyLong(), anyMap(), anyLong(), anyLong());
      return thrown;
    }
  }

  private static void setCalculationExecutor(MetricsCollector collector, ExecutorService executor)
      throws ReflectiveOperationException {
    Field field = MetricsCollector.class.getDeclaredField("metricsCalculationExecutor");
    field.setAccessible(true);
    field.set(collector, executor);
  }

  private BaseMetalake metalake() {
    return BaseMetalake.builder()
        .withId(mockId)
        .withName(metalakeName)
        .withVersion(SchemaVersion.V_0_1)
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
        .build();
  }

  private NameIdentifier storedProbe() throws IOException {
    SchemaEntity probe =
        SchemaEntity.builder()
            .withId(99L)
            .withName("probe")
            .withNamespace(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName))
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofSchema(metalakeName, relationalCatalogName),
            SchemaEntity.class,
            Entity.EntityType.SCHEMA))
        .thenReturn(ImmutableList.of(probe));
    return probe.nameIdentifier();
  }

  private void mockStoredRelationalSchema(long schemaId) throws IOException {
    SchemaEntity schema =
        SchemaEntity.builder()
            .withId(schemaId)
            .withName(relationalSchemaName)
            .withNamespace(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName))
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    when(store.list(
            NamespaceUtil.ofSchema(metalakeName, relationalCatalogName),
            SchemaEntity.class,
            Entity.EntityType.SCHEMA))
        .thenReturn(ImmutableList.of(schema));
  }

  private MetalakeSnapshot collectSnapshot() throws Exception {
    try (MockedStatic<GravitinoEnv> mockedEnv = Mockito.mockStatic(GravitinoEnv.class);
        MockedStatic<MetricDataService> mockedService =
            Mockito.mockStatic(MetricDataService.class)) {
      mockedEnv.when(GravitinoEnv::getInstance).thenReturn(gravitinoEnv);
      when(gravitinoEnv.lockManager()).thenReturn(LOCKS);
      mockedService.when(MetricDataService::getInstance).thenReturn(metricDataService);
      MetricsCollector collector = MetricsCollector.getInstance();
      collector.initialize(serverConfig, gravitinoEnv);
      return collector.loadAllDataForMetalake(metalake());
    }
  }

  private void mockLoadCatalog() throws Exception {
    catalogWrapper = mock(CatalogManager.CatalogWrapper.class);
    doAnswer(
            invocation -> {
              ThrowableFunction<CatalogManager.CatalogWrapper, Object> operation =
                  invocation.getArgument(1);
              return operation.apply(catalogWrapper);
            })
        .when(catalogManager)
        .doWithCatalogWrapper(
            eq(NameIdentifierUtil.ofCatalog(metalakeName, relationalCatalogName)), any());

    when(catalogWrapper.capabilities()).thenReturn(Capability.DEFAULT);
    when(catalogWrapper.<Boolean>doWithSchemaOps(any())).thenReturn(true);
    when(catalogWrapper.<Boolean>doWithViewOps(any())).thenReturn(true);
  }

  private static void assertMetric(
      List<MetricPO> metrics, String name, Double value, MetricState state) {
    MetricPO metric =
        metrics.stream()
            .filter(candidate -> candidate.getMetricName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Metric not found: " + name));
    assertEquals(value, metric.getMetricValue());
    assertEquals(state, metric.getMetricState());
  }

  private void mockListUserFromStore() throws IOException {
    when(store.list(NamespaceUtil.ofUser(metalakeName), UserEntity.class, Entity.EntityType.USER))
        .thenReturn(ImmutableList.of());
  }

  private void mockLoadRoleSecurableObjectsRel() throws IOException {
    when(store.list(NamespaceUtil.ofRole(metalakeName), RoleEntity.class, Entity.EntityType.ROLE))
        .thenReturn(ImmutableList.of());
  }

  private void mockListOwners() throws IOException {
    when(metricDataService.listOwnerNameRelsByMetalakeId(eq(mockId)))
        .thenReturn(Collections.emptyList());
  }

  private void mockSchemaDispatcher() {
    NameIdentifier relationalSchemaIdent =
        NameIdentifierUtil.ofSchema(metalakeName, relationalCatalogName, relationalSchemaName);
    when(schemaDispatcher.listSchemas(
            eq(NamespaceUtil.ofSchema(metalakeName, relationalCatalogName))))
        .thenReturn(new NameIdentifier[] {relationalSchemaIdent});
  }

  private void mockTableDispatcher() {
    NameIdentifier tableIdent =
        NameIdentifierUtil.ofTable(
            metalakeName, relationalCatalogName, relationalSchemaName, tableName);
    when(tableDispatcher.listTables(
            eq(NamespaceUtil.ofTable(metalakeName, relationalCatalogName, relationalSchemaName))))
        .thenReturn(new NameIdentifier[] {tableIdent});
  }

  private void mockViewDispatcher() {
    NameIdentifier viewIdent =
        NameIdentifierUtil.ofView(
            metalakeName, relationalCatalogName, relationalSchemaName, "view");
    when(viewDispatcher.listViews(
            eq(NamespaceUtil.ofView(metalakeName, relationalCatalogName, relationalSchemaName))))
        .thenReturn(new NameIdentifier[] {viewIdent});
  }

  private void mockListFunctionsFromStore() throws IOException {
    when(store.list(
            eq(NamespaceUtil.ofFunction(metalakeName, relationalCatalogName, relationalSchemaName)),
            eq(FunctionEntity.class),
            eq(Entity.EntityType.FUNCTION)))
        .thenReturn(ImmutableList.of());
  }
}
