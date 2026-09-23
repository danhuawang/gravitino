/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;

class TestMetricsAuthorizationIndex {
  private static final String METALAKE = "visibility_metalake";
  private static final String CATALOG = "catalog";
  private static final String OWNED_SCHEMA = "owned_schema";
  private static final String DENY_SCHEMA = "denied_schema";

  @Test
  void testProjectsDenyAndDirectOwnershipWithCalculatorParity() throws IOException {
    Fixture fixture = fixture();
    MetricsAuthorizationIndex index = new MetricsAuthorizationIndex(fixture.snapshot);

    MetricsVisibility reader = index.visibility(index.profile("reader", 101L));
    assertEquals(ImmutableSet.of(fixture.catalog), reader.catalogNodes());
    assertEquals(
        ImmutableSet.of(fixture.allowedTable, fixture.ownerTable, fixture.filterOwnerTable),
        reader.assetNodes(MetadataObject.Type.TABLE));
    assertEquals(ImmutableSet.of(fixture.view), reader.assetNodes(MetadataObject.Type.VIEW));
    assertEquals(
        ImmutableSet.of(fixture.function), reader.assetNodes(MetadataObject.Type.FUNCTION));
    assertEquals(ImmutableSet.of(fixture.fileset), reader.assetNodes(MetadataObject.Type.FILESET));
    assertEquals(ImmutableSet.of(fixture.topic), reader.assetNodes(MetadataObject.Type.TOPIC));
    assertEquals(ImmutableSet.of(fixture.model), reader.assetNodes(MetadataObject.Type.MODEL));
    assertEquals(
        ImmutableSet.of(fixture.ownedSchema, fixture.denySchema),
        reader.schemaNodesForDirectCounts());
    assertTrue(reader.assetNodesForDirectCounts().contains(fixture.allowedTable));
    assertFalse(reader.assetNodesForDirectCounts().contains(fixture.deniedTable));
    assertFalse(reader.schemaNodesForDirectCounts().contains(fixture.dottedSchema));
    assertFalse(
        reader.assetNodes(MetadataObject.Type.TABLE).contains(fixture.tableUnderDottedSchema));
    assertFalse(reader.assetNodes(MetadataObject.Type.TABLE).contains(fixture.dottedTable));

    MetricsVisibility catalogOwner = index.visibility(index.profile("catalog_owner", 102L));
    assertEquals(fixture.tables, catalogOwner.assetNodes(MetadataObject.Type.TABLE));
    assertEquals(ImmutableSet.of(fixture.view), catalogOwner.assetNodes(MetadataObject.Type.VIEW));

    MetricsVisibility schemaOwner = index.visibility(index.profile("schema_owner", 103L));
    assertEquals(fixture.ownedSchemaTables, schemaOwner.assetNodes(MetadataObject.Type.TABLE));
    assertEquals(
        ImmutableSet.of(fixture.function), schemaOwner.assetNodes(MetadataObject.Type.FUNCTION));
    assertEquals(ImmutableSet.of(fixture.ownedSchema), schemaOwner.schemaNodesForDirectCounts());

    MetricsVisibility tableOwner = index.visibility(index.profile("table_owner", 104L));
    assertEquals(
        ImmutableSet.of(fixture.ownerTable), tableOwner.assetNodes(MetadataObject.Type.TABLE));
    assertTrue(tableOwner.assetNodes(MetadataObject.Type.VIEW).isEmpty());

    MetricsVisibility filterOwner = index.visibility(index.profile("filter_owner", 105L));
    assertTrue(filterOwner.catalogNodes().isEmpty());
    assertTrue(filterOwner.assetNodes(MetadataObject.Type.TABLE).isEmpty());
    assertEquals(
        ImmutableSet.of(fixture.filterOwnerTable), filterOwner.assetNodesForDirectCounts());

    assertCalculatorParity(fixture.snapshot, index);
  }

  @Test
  void testUnexpectedPrivilegeFailurePropagates() {
    Fixture fixture = fixture();
    SecurableObject policy = mock(SecurableObject.class);
    when(policy.type()).thenReturn(MetadataObject.Type.CATALOG);
    when(policy.name()).thenReturn(CATALOG);
    when(policy.fullName()).thenReturn(CATALOG);
    IllegalStateException failure = new IllegalStateException("broken privilege projection");
    when(policy.privileges()).thenThrow(failure);
    MetalakeSnapshot snapshot = spy(fixture.snapshot);
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(999L, ImmutableList.of(policy)));

    assertSame(
        failure,
        assertThrows(IllegalStateException.class, () -> new MetricsAuthorizationIndex(snapshot)));
  }

  @Test
  void testProjectsHierarchicalSchemaInheritanceWithCalculatorParity() throws IOException {
    String separator = HierarchicalSchemaUtil.schemaSeparator();
    String analytics = "analytics";
    String analyticsFinance = analytics + separator + "finance";
    String analyticsRisk = analytics + separator + "risk";
    String operations = "operations";
    String operationsFinance = operations + separator + "finance";

    AssetNode root =
        new AssetNode(101L, METALAKE, MetadataObject.Type.METALAKE, null, Collections.emptySet());
    AssetNode catalog =
        new AssetNode(102L, CATALOG, MetadataObject.Type.CATALOG, root, Collections.emptySet());
    AssetNode analyticsNode =
        hierarchicalSchema(103L, analytics, catalog, ImmutableSet.of(owner("parent_owner")));
    AssetNode analyticsFinanceNode =
        hierarchicalSchema(104L, analyticsFinance, analyticsNode, Collections.emptySet());
    AssetNode analyticsRiskNode =
        hierarchicalSchema(105L, analyticsRisk, analyticsNode, Collections.emptySet());
    AssetNode operationsNode =
        hierarchicalSchema(106L, operations, catalog, Collections.emptySet());
    AssetNode operationsFinanceNode =
        hierarchicalSchema(107L, operationsFinance, operationsNode, Collections.emptySet());
    AssetNode financeTable =
        asset(110L, "finance_table", MetadataObject.Type.TABLE, analyticsFinanceNode);
    AssetNode riskTable = asset(111L, "risk_table", MetadataObject.Type.TABLE, analyticsRiskNode);
    AssetNode operationsTable =
        asset(112L, "operations_table", MetadataObject.Type.TABLE, operationsFinanceNode);

    root.addChild(catalog);
    catalog.addChildren(Arrays.asList(analyticsNode, operationsNode));
    analyticsNode.addChildren(Arrays.asList(analyticsFinanceNode, analyticsRiskNode));
    operationsNode.addChild(operationsFinanceNode);
    analyticsFinanceNode.addChild(financeTable);
    analyticsRiskNode.addChild(riskTable);
    operationsFinanceNode.addChild(operationsTable);

    SecurableObject catalogObject = SecurableObjects.ofCatalog(CATALOG, Collections.emptyList());
    Map<Long, List<SecurableObject>> rolePolicies =
        ImmutableMap.of(
            20L,
            ImmutableList.of(
                SecurableObjects.ofMetalake(
                    METALAKE, ImmutableList.of(Privileges.UseCatalog.allow()))),
            21L,
            ImmutableList.of(
                SecurableObjects.ofSchema(
                    catalogObject,
                    analytics,
                    ImmutableList.of(
                        Privileges.UseSchema.allow(), Privileges.SelectTable.allow()))),
            22L,
            ImmutableList.of(
                SecurableObjects.ofMetalake(
                    METALAKE,
                    ImmutableList.of(
                        Privileges.UseCatalog.allow(),
                        Privileges.UseSchema.allow(),
                        Privileges.SelectTable.allow()))),
            23L,
            ImmutableList.of(
                SecurableObjects.ofSchema(
                    catalogObject, analytics, ImmutableList.of(Privileges.SelectTable.deny()))));

    Map<Long, AssetNode> nodesById = new HashMap<>();
    Arrays.asList(
            root,
            catalog,
            analyticsNode,
            analyticsFinanceNode,
            analyticsRiskNode,
            operationsNode,
            operationsFinanceNode,
            financeTable,
            riskTable,
            operationsTable)
        .forEach(node -> nodesById.put(node.getId(), node));

    MetalakeSnapshot snapshot =
        MetalakeSnapshot.builder()
            .assetTreeRoot(root)
            .assetNodeById(nodesById)
            .userNameToUserId(
                ImmutableMap.of("parent_grant", 201L, "parent_deny", 202L, "parent_owner", 203L))
            .userIdToRoleIds(
                ImmutableMap.of(
                    201L,
                    ImmutableSet.of(20L, 21L),
                    202L,
                    ImmutableSet.of(22L, 23L),
                    203L,
                    ImmutableSet.of(20L)))
            .roleIdToSecurableObjects(rolePolicies)
            .taggedObjectIds(Collections.emptySet())
            .catalogNodes(ImmutableSet.of(catalog))
            .schemaNodes(
                ImmutableSet.of(
                    analyticsNode,
                    analyticsFinanceNode,
                    analyticsRiskNode,
                    operationsNode,
                    operationsFinanceNode))
            .tableNodes(ImmutableSet.of(financeTable, riskTable, operationsTable))
            .viewNodes(Collections.emptySet())
            .functionNodes(Collections.emptySet())
            .filesetNodes(Collections.emptySet())
            .topicNodes(Collections.emptySet())
            .modelNodes(Collections.emptySet())
            .enabledPolicyObjectIds(Collections.emptySet())
            .policyCount(0L)
            .disabledPolicyCount(0L)
            .associatedTagCountsByCatalogId(Collections.emptyMap())
            .associatedPolicyCountsByCatalogId(Collections.emptyMap())
            .failedCatalogNames(Collections.emptySet())
            .failedSchemaIdentifiers(Collections.emptyMap())
            .catalogTypes(ImmutableMap.of(CATALOG, Catalog.Type.RELATIONAL))
            .catalogProviders(ImmutableMap.of(CATALOG, "hive"))
            .viewListingSupportByCatalog(ImmutableMap.of(CATALOG, true))
            .build();

    MetricsAuthorizationIndex index = new MetricsAuthorizationIndex(snapshot);
    assertEquals(
        ImmutableSet.of(financeTable, riskTable),
        index
            .visibility(index.profile("parent_grant", 201L))
            .assetNodes(MetadataObject.Type.TABLE));
    assertEquals(
        ImmutableSet.of(operationsTable),
        index.visibility(index.profile("parent_deny", 202L)).assetNodes(MetadataObject.Type.TABLE));
    assertEquals(
        ImmutableSet.of(financeTable, riskTable),
        index
            .visibility(index.profile("parent_owner", 203L))
            .assetNodes(MetadataObject.Type.TABLE));
    assertCalculatorParity(snapshot, index);
  }

  private static void assertCalculatorParity(
      MetalakeSnapshot snapshot, MetricsAuthorizationIndex index) throws IOException {
    MetricsCollector.getInstance().getMetalakeSnapshots().put(METALAKE, snapshot);
    MemoizedJcasbinAuthorizer authorizer = new MemoizedJcasbinAuthorizer();
    try {
      authorizer.initialize();
      authorizer.preloadSnapshotPolicies(METALAKE);
      MetricsCalculator calculator = new MetricsCalculator(snapshot);
      for (Map.Entry<String, Long> user : snapshot.getUserNameToUserId().entrySet()) {
        List<MetricPO> expected = calculator.calculateMetricsForUser(user.getKey(), authorizer);
        List<MetricPO> actual =
            calculator.calculateMetricsForVisibility(
                user.getKey(),
                index.visibility(index.profile(user.getKey(), user.getValue())),
                authorizer);
        assertMetricsEqual(user.getKey(), expected, actual);
      }
    } finally {
      authorizer.close();
      MetricsCollector.getInstance().getMetalakeSnapshots().remove(METALAKE);
    }
  }

  private static AssetNode hierarchicalSchema(
      long id, String name, AssetNode parent, Set<Owner> owners) {
    return new AssetNode(
        id,
        name,
        NameIdentifierUtil.ofSchema(METALAKE, CATALOG, name),
        MetadataObject.Type.SCHEMA,
        parent,
        owners);
  }

  private static void assertMetricsEqual(
      String username, List<MetricPO> expected, List<MetricPO> actual) {
    Function<List<MetricPO>, Map<String, MetricPO>> byName =
        metrics ->
            metrics.stream()
                .collect(Collectors.toMap(MetricPO::getMetricName, Function.identity()));
    Map<String, MetricPO> expectedByName = byName.apply(expected);
    Map<String, MetricPO> actualByName = byName.apply(actual);
    assertEquals(expectedByName.keySet(), actualByName.keySet(), username);
    expectedByName.forEach(
        (name, expectedMetric) -> {
          MetricPO actualMetric = actualByName.get(name);
          assertEquals(
              expectedMetric.getMetricValue(),
              actualMetric.getMetricValue(),
              username + ": " + name);
          assertEquals(
              expectedMetric.getMetricState(),
              actualMetric.getMetricState(),
              username + ": " + name);
          assertEquals(
              expectedMetric.getMetricMessage(),
              actualMetric.getMetricMessage(),
              username + ": " + name);
        });
  }

  private static Fixture fixture() {
    Owner catalogOwner = owner("catalog_owner");
    Owner schemaOwner = owner("schema_owner");
    Owner tableOwner = owner("table_owner");
    Owner filterOwner = owner("filter_owner");

    AssetNode root =
        new AssetNode(1L, METALAKE, MetadataObject.Type.METALAKE, null, Collections.emptySet());
    AssetNode catalog =
        new AssetNode(
            2L, CATALOG, MetadataObject.Type.CATALOG, root, ImmutableSet.of(catalogOwner));
    AssetNode ownedSchema =
        new AssetNode(
            3L, OWNED_SCHEMA, MetadataObject.Type.SCHEMA, catalog, ImmutableSet.of(schemaOwner));
    AssetNode denySchema = asset(5L, DENY_SCHEMA, MetadataObject.Type.SCHEMA, catalog);
    AssetNode dottedSchema = asset(6L, "external.schema", MetadataObject.Type.SCHEMA, catalog);
    AssetNode allowedTable = asset(10L, "allowed", MetadataObject.Type.TABLE, ownedSchema);
    AssetNode deniedTable = asset(11L, "denied", MetadataObject.Type.TABLE, denySchema);
    AssetNode ownerTable =
        new AssetNode(
            12L,
            "owner_table",
            MetadataObject.Type.TABLE,
            ownedSchema,
            ImmutableSet.of(tableOwner));
    AssetNode filterOwnerTable =
        new AssetNode(
            13L,
            "filter_owner_table",
            MetadataObject.Type.TABLE,
            ownedSchema,
            ImmutableSet.of(filterOwner));
    AssetNode view = asset(14L, "view", MetadataObject.Type.VIEW, ownedSchema);
    AssetNode function = asset(15L, "function", MetadataObject.Type.FUNCTION, ownedSchema);
    AssetNode fileset = asset(16L, "fileset", MetadataObject.Type.FILESET, ownedSchema);
    AssetNode topic = asset(17L, "topic", MetadataObject.Type.TOPIC, ownedSchema);
    AssetNode model = asset(18L, "model", MetadataObject.Type.MODEL, ownedSchema);
    AssetNode tableUnderDottedSchema =
        asset(19L, "legal_table", MetadataObject.Type.TABLE, dottedSchema);
    AssetNode dottedTable = asset(20L, "object.with.dot", MetadataObject.Type.TABLE, ownedSchema);
    root.addChild(catalog);
    catalog.addChild(ownedSchema);
    catalog.addChild(denySchema);
    catalog.addChild(dottedSchema);
    ownedSchema.addChildren(
        Arrays.asList(
            allowedTable, ownerTable, filterOwnerTable, view, function, fileset, topic, model));
    denySchema.addChild(deniedTable);
    dottedSchema.addChild(tableUnderDottedSchema);
    ownedSchema.addChild(dottedTable);

    List<Privilege> readerPrivileges =
        Arrays.asList(
            Privileges.UseCatalog.allow(),
            Privileges.UseSchema.allow(),
            Privileges.SelectTable.allow(),
            Privileges.SelectView.allow(),
            Privileges.ExecuteFunction.allow(),
            Privileges.ReadFileset.allow(),
            Privileges.ConsumeTopic.allow(),
            Privileges.UseModel.allow());
    SecurableObject catalogObject = SecurableObjects.ofCatalog(CATALOG, Collections.emptyList());
    SecurableObject schemaObject =
        SecurableObjects.ofSchema(catalogObject, DENY_SCHEMA, Collections.emptyList());
    Map<Long, List<SecurableObject>> rolePolicies =
        ImmutableMap.of(
            10L,
            ImmutableList.of(SecurableObjects.ofMetalake(METALAKE, readerPrivileges)),
            11L,
            ImmutableList.of(
                SecurableObjects.ofTable(
                    schemaObject,
                    deniedTable.getName(),
                    ImmutableList.of(Privileges.SelectTable.deny()))),
            12L,
            ImmutableList.of(
                SecurableObjects.ofMetalake(
                    METALAKE, ImmutableList.of(Privileges.UseCatalog.allow()))),
            13L,
            ImmutableList.of(
                SecurableObjects.ofMetalake(
                    METALAKE,
                    ImmutableList.of(
                        Privileges.UseCatalog.allow(), Privileges.UseSchema.allow()))));

    Map<Long, AssetNode> nodesById = new HashMap<>();
    Arrays.asList(
            root,
            catalog,
            ownedSchema,
            denySchema,
            dottedSchema,
            allowedTable,
            deniedTable,
            ownerTable,
            filterOwnerTable,
            view,
            function,
            fileset,
            topic,
            model,
            tableUnderDottedSchema,
            dottedTable)
        .forEach(node -> nodesById.put(node.getId(), node));

    MetalakeSnapshot snapshot =
        MetalakeSnapshot.builder()
            .assetTreeRoot(root)
            .assetNodeById(nodesById)
            .userNameToUserId(
                ImmutableMap.of(
                    "reader",
                    101L,
                    "catalog_owner",
                    102L,
                    "schema_owner",
                    103L,
                    "table_owner",
                    104L,
                    "filter_owner",
                    105L))
            .userIdToRoleIds(
                ImmutableMap.of(
                    101L,
                    ImmutableSet.of(10L, 11L),
                    103L,
                    ImmutableSet.of(12L),
                    104L,
                    ImmutableSet.of(13L)))
            .roleIdToSecurableObjects(rolePolicies)
            .taggedObjectIds(Collections.emptySet())
            .catalogNodes(ImmutableSet.of(catalog))
            .schemaNodes(ImmutableSet.of(ownedSchema, denySchema, dottedSchema))
            .tableNodes(
                ImmutableSet.of(
                    allowedTable,
                    deniedTable,
                    ownerTable,
                    filterOwnerTable,
                    tableUnderDottedSchema,
                    dottedTable))
            .viewNodes(ImmutableSet.of(view))
            .functionNodes(ImmutableSet.of(function))
            .filesetNodes(ImmutableSet.of(fileset))
            .topicNodes(ImmutableSet.of(topic))
            .modelNodes(ImmutableSet.of(model))
            .enabledPolicyObjectIds(Collections.emptySet())
            .policyCount(0L)
            .disabledPolicyCount(0L)
            .associatedTagCountsByCatalogId(Collections.emptyMap())
            .associatedPolicyCountsByCatalogId(Collections.emptyMap())
            .failedCatalogNames(Collections.emptySet())
            .failedSchemaIdentifiers(Collections.emptyMap())
            .catalogTypes(ImmutableMap.of(CATALOG, Catalog.Type.RELATIONAL))
            .catalogProviders(ImmutableMap.of(CATALOG, "hive"))
            .viewListingSupportByCatalog(ImmutableMap.of(CATALOG, true))
            .build();
    return new Fixture(
        snapshot,
        catalog,
        ownedSchema,
        denySchema,
        dottedSchema,
        allowedTable,
        deniedTable,
        ownerTable,
        filterOwnerTable,
        view,
        function,
        fileset,
        topic,
        model,
        tableUnderDottedSchema,
        dottedTable);
  }

  private static AssetNode asset(long id, String name, MetadataObject.Type type, AssetNode parent) {
    return new AssetNode(id, name, type, parent, Collections.emptySet());
  }

  private static Owner owner(String name) {
    Owner owner = mock(Owner.class);
    when(owner.name()).thenReturn(name);
    when(owner.type()).thenReturn(Owner.Type.USER);
    return owner;
  }

  private static final class Fixture {
    private final MetalakeSnapshot snapshot;
    private final AssetNode catalog;
    private final AssetNode ownedSchema;
    private final AssetNode denySchema;
    private final AssetNode dottedSchema;
    private final AssetNode allowedTable;
    private final AssetNode deniedTable;
    private final AssetNode ownerTable;
    private final AssetNode filterOwnerTable;
    private final AssetNode view;
    private final AssetNode function;
    private final AssetNode fileset;
    private final AssetNode topic;
    private final AssetNode model;
    private final AssetNode tableUnderDottedSchema;
    private final AssetNode dottedTable;
    private final Set<AssetNode> tables;
    private final Set<AssetNode> ownedSchemaTables;

    private Fixture(
        MetalakeSnapshot snapshot,
        AssetNode catalog,
        AssetNode ownedSchema,
        AssetNode denySchema,
        AssetNode dottedSchema,
        AssetNode allowedTable,
        AssetNode deniedTable,
        AssetNode ownerTable,
        AssetNode filterOwnerTable,
        AssetNode view,
        AssetNode function,
        AssetNode fileset,
        AssetNode topic,
        AssetNode model,
        AssetNode tableUnderDottedSchema,
        AssetNode dottedTable) {
      this.snapshot = snapshot;
      this.catalog = catalog;
      this.ownedSchema = ownedSchema;
      this.denySchema = denySchema;
      this.dottedSchema = dottedSchema;
      this.allowedTable = allowedTable;
      this.deniedTable = deniedTable;
      this.ownerTable = ownerTable;
      this.filterOwnerTable = filterOwnerTable;
      this.view = view;
      this.function = function;
      this.fileset = fileset;
      this.topic = topic;
      this.model = model;
      this.tableUnderDottedSchema = tableUnderDottedSchema;
      this.dottedTable = dottedTable;
      this.tables = ImmutableSet.of(allowedTable, deniedTable, ownerTable, filterOwnerTable);
      this.ownedSchemaTables = ImmutableSet.of(allowedTable, ownerTable, filterOwnerTable);
    }
  }
}
