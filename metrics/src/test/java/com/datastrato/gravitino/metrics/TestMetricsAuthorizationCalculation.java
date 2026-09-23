/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Owner;
import org.junit.jupiter.api.Test;

class TestMetricsAuthorizationCalculation {

  @Test
  void testCalculatesEachAccessProfileOnceAndReusesMetalakeOwnerResult() {
    Owner owner = mock(Owner.class);
    when(owner.name()).thenReturn("owner");
    when(owner.type()).thenReturn(Owner.Type.USER);
    AssetNode root =
        new AssetNode(1L, "metalake", MetadataObject.Type.METALAKE, null, ImmutableSet.of(owner));
    MetalakeSnapshot snapshot = mock(MetalakeSnapshot.class);
    when(snapshot.getAssetTreeRoot()).thenReturn(root);
    when(snapshot.getUserNameToUserId())
        .thenReturn(ImmutableMap.of("first", 101L, "second", 102L, "owner", 103L));
    when(snapshot.getUserIdToRoleIds())
        .thenReturn(
            ImmutableMap.of(
                101L,
                ImmutableSet.of(10L),
                102L,
                ImmutableSet.of(10L),
                103L,
                ImmutableSet.of(10L)));
    when(snapshot.getRoleIdToSecurableObjects())
        .thenReturn(ImmutableMap.of(10L, Collections.emptyList()));
    when(snapshot.getAssetNodeById()).thenReturn(ImmutableMap.of(1L, root));
    when(snapshot.getAssetNodeByIdent()).thenReturn(ImmutableMap.of(root.getNameIdent(), root));
    when(snapshot.getCatalogNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getSchemaNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getTableNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getViewNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getFunctionNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getFilesetNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getTopicNodes()).thenReturn(Collections.emptySet());
    when(snapshot.getModelNodes()).thenReturn(Collections.emptySet());
    MetricsAuthorizationIndex authorizationIndex = new MetricsAuthorizationIndex(snapshot);

    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    MetricsCalculator calculator = mock(MetricsCalculator.class);
    List<MetricPO> sharedMetrics = Collections.singletonList(mock(MetricPO.class));
    List<MetricPO> ownerMetrics = Collections.singletonList(mock(MetricPO.class));
    when(calculator.calculateMetricsForVisibility(
            anyString(), any(MetricsVisibility.class), same(authorizer)))
        .thenAnswer(
            invocation ->
                invocation.<String>getArgument(0).equals("owner") ? ownerMetrics : sharedMetrics);
    AtomicInteger submittedTasks = new AtomicInteger();

    Map<Long, List<MetricPO>> result =
        MetricsCollector.calculateAuthorizedMetrics(
            snapshot,
            calculator,
            authorizer,
            authorizationIndex,
            task -> {
              submittedTasks.incrementAndGet();
              task.run();
            });

    assertEquals(2, submittedTasks.get());
    assertEquals(4, result.size());
    assertSame(sharedMetrics, result.get(101L));
    assertSame(sharedMetrics, result.get(102L));
    assertSame(ownerMetrics, result.get(103L));
    assertSame(ownerMetrics, result.get(MetricsCollector.MOCK_USER_ID_FOR_METALAKE_OWNER));
    verify(calculator, times(2))
        .calculateMetricsForVisibility(anyString(), any(MetricsVisibility.class), same(authorizer));
  }
}
