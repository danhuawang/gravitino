/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.metrics.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import java.util.Collections;
import org.apache.gravitino.MetalakeChange;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.AlterMetalakeEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserEvent;
import org.apache.gravitino.listener.api.info.MetalakeInfo;
import org.apache.gravitino.storage.relational.service.MetalakeMetaService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TestDashboardMetricsEventListener {

  @Test
  void testMode() {
    DashboardMetricsEventListener listener = new DashboardMetricsEventListener();
    assertEquals(EventListenerPlugin.Mode.ASYNC_ISOLATED, listener.mode());
  }

  @Test
  void testSuccessfulMutationMarksMetalakeDirty() {
    MetricDataService metricDataService = mock(MetricDataService.class);
    MetalakeMetaService metalakeMetaService = mock(MetalakeMetaService.class);
    Event event = successfulEvent(OperationType.CREATE_TABLE);
    when(event.identifier())
        .thenReturn(NameIdentifier.of("metalake", "catalog", "schema", "table"));
    when(metalakeMetaService.getMetalakeIdByName("metalake")).thenReturn(10L);

    try (MockedStatic<MetricDataService> metricServiceStatic =
            Mockito.mockStatic(MetricDataService.class);
        MockedStatic<MetalakeMetaService> metalakeServiceStatic =
            Mockito.mockStatic(MetalakeMetaService.class)) {
      metricServiceStatic.when(MetricDataService::getInstance).thenReturn(metricDataService);
      metalakeServiceStatic.when(MetalakeMetaService::getInstance).thenReturn(metalakeMetaService);

      DashboardMetricsEventListener listener = new DashboardMetricsEventListener();
      listener.init(Collections.emptyMap());
      listener.onPostEvent(event);

      verify(metricDataService).markMetalakeDirty(10L, event.eventTime());
    }
  }

  @Test
  void testReadFailureAndNullIdentifierAreIgnored() {
    MetricDataService metricDataService = mock(MetricDataService.class);
    Event readEvent = successfulEvent(OperationType.LIST_TABLE);
    Event failureEvent = mock(Event.class);
    when(failureEvent.operationStatus()).thenReturn(OperationStatus.FAILURE);
    when(failureEvent.operationType()).thenReturn(OperationType.CREATE_TABLE);
    Event nullIdentifierEvent = successfulEvent(OperationType.ADD_USER);
    when(nullIdentifierEvent.identifier()).thenReturn(null);

    try (MockedStatic<MetricDataService> metricServiceStatic =
        Mockito.mockStatic(MetricDataService.class)) {
      metricServiceStatic.when(MetricDataService::getInstance).thenReturn(metricDataService);
      DashboardMetricsEventListener listener = new DashboardMetricsEventListener();
      listener.init(Collections.emptyMap());

      listener.onPostEvent(readEvent);
      listener.onPostEvent(failureEvent);
      listener.onPostEvent(nullIdentifierEvent);

      verify(metricDataService, never()).markMetalakeDirty(anyLong(), anyLong());
    }
  }

  @Test
  void testRenameUsesUpdatedMetalakeNameAndStorageFailureIsContained() {
    MetricDataService metricDataService = mock(MetricDataService.class);
    MetalakeMetaService metalakeMetaService = mock(MetalakeMetaService.class);
    AlterMetalakeEvent renameEvent =
        new AlterMetalakeEvent(
            "user",
            NameIdentifier.of("old_name"),
            new MetalakeChange[0],
            new MetalakeInfo("new_name", null, Collections.emptyMap(), null));
    when(metalakeMetaService.getMetalakeIdByName("new_name")).thenReturn(20L);
    Mockito.doThrow(new RuntimeException("database unavailable"))
        .when(metricDataService)
        .markMetalakeDirty(20L, renameEvent.eventTime());

    try (MockedStatic<MetricDataService> metricServiceStatic =
            Mockito.mockStatic(MetricDataService.class);
        MockedStatic<MetalakeMetaService> metalakeServiceStatic =
            Mockito.mockStatic(MetalakeMetaService.class)) {
      metricServiceStatic.when(MetricDataService::getInstance).thenReturn(metricDataService);
      metalakeServiceStatic.when(MetalakeMetaService::getInstance).thenReturn(metalakeMetaService);
      DashboardMetricsEventListener listener = new DashboardMetricsEventListener();
      listener.init(Collections.emptyMap());

      assertDoesNotThrow(() -> listener.onPostEvent(renameEvent));
      verify(metalakeMetaService).getMetalakeIdByName("new_name");
    }
  }

  @Test
  void testScimMutationIsIncludedAndUnrelatedWritesAreExcluded() {
    MetricDataService metricDataService = mock(MetricDataService.class);
    MetalakeMetaService metalakeMetaService = mock(MetalakeMetaService.class);
    ScimAddUserEvent scimEvent =
        new ScimAddUserEvent("initiator", "metalake", "new_user", null, null);
    when(metalakeMetaService.getMetalakeIdByName("metalake")).thenReturn(30L);

    try (MockedStatic<MetricDataService> metricServiceStatic =
            Mockito.mockStatic(MetricDataService.class);
        MockedStatic<MetalakeMetaService> metalakeServiceStatic =
            Mockito.mockStatic(MetalakeMetaService.class)) {
      metricServiceStatic.when(MetricDataService::getInstance).thenReturn(metricDataService);
      metalakeServiceStatic.when(MetalakeMetaService::getInstance).thenReturn(metalakeMetaService);
      DashboardMetricsEventListener listener = new DashboardMetricsEventListener();
      listener.init(Collections.emptyMap());

      listener.onPostEvent(scimEvent);
      verify(metricDataService).markMetalakeDirty(30L, scimEvent.eventTime());

      for (OperationType operationType :
          new OperationType[] {
            OperationType.ADD_PARTITION,
            OperationType.CREATE_VIEW,
            OperationType.REGISTER_FUNCTION,
            OperationType.CREATE_POLICY,
            OperationType.LINK_MODEL_VERSION,
            OperationType.DROP_METALAKE,
            OperationType.DISABLE_METALAKE
          }) {
        Event ignoredEvent = successfulEvent(operationType);
        when(ignoredEvent.identifier()).thenReturn(NameIdentifier.of("metalake"));
        listener.onPostEvent(ignoredEvent);
      }
      verifyNoMoreInteractions(metricDataService);
    }
  }

  private static Event successfulEvent(OperationType operationType) {
    Event event = mock(Event.class);
    when(event.operationStatus()).thenReturn(OperationStatus.SUCCESS);
    when(event.operationType()).thenReturn(operationType);
    when(event.eventTime()).thenReturn(1234L);
    return event;
  }
}
