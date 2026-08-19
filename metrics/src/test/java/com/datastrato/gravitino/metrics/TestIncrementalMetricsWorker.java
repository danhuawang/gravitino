/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.storage.relational.MetricDirtyPO;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.server.ServerConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestIncrementalMetricsWorker {

  @Test
  void testDueMarkerIsCollectedAndDeletedByRevision() throws Exception {
    MetricsCollector collector = mock(MetricsCollector.class);
    MetricDataService service = mock(MetricDataService.class);
    IncrementalMetricsWorker worker = newWorker(collector, service);
    MetricDirtyPO dirty = dirty(10L, 3L, System.currentTimeMillis() - 2_000, null, 0);
    BaseMetalake metalake = mock(BaseMetalake.class);
    when(service.listDueDirtyMetalakes(anyLong(), anyLong(), anyLong()))
        .thenReturn(Collections.singletonList(dirty));
    when(service.getDirtyMetalake(10L)).thenReturn(dirty);
    when(collector.metalakeLock(10L)).thenReturn(new Object());
    when(collector.findActiveMetalake(10L)).thenReturn(Optional.of(metalake));
    runSubmittedTasksImmediately(collector);

    worker.pollOnce();

    verify(collector)
        .collectAndPublish(eq(metalake), eq(MetricsCollector.PublishMode.CURRENT_ONLY), anyLong());
    verify(service).deleteDirtyIfRevision(10L, 3L);
    worker.close();
  }

  @Test
  void testLatestEventIsRecheckedAfterScheduling() throws Exception {
    MetricsCollector collector = mock(MetricsCollector.class);
    MetricDataService service = mock(MetricDataService.class);
    IncrementalMetricsWorker worker = newWorker(collector, service);
    MetricDirtyPO selected = dirty(10L, 1L, System.currentTimeMillis() - 2_000, null, 0);
    MetricDirtyPO latest = dirty(10L, 2L, System.currentTimeMillis(), null, 0);
    when(service.listDueDirtyMetalakes(anyLong(), anyLong(), anyLong()))
        .thenReturn(Collections.singletonList(selected));
    when(service.getDirtyMetalake(10L)).thenReturn(latest);
    when(collector.metalakeLock(10L)).thenReturn(new Object());
    runSubmittedTasksImmediately(collector);

    worker.pollOnce();

    verify(collector, never()).collectAndPublish(any(), any(), anyLong());
    verify(service, never()).deleteDirtyIfRevision(anyLong(), anyLong());
    worker.close();
  }

  @Test
  void testFailureSchedulesRetryAndInFlightSuppressesDuplicateSubmission() throws Exception {
    MetricsCollector collector = mock(MetricsCollector.class);
    MetricDataService service = mock(MetricDataService.class);
    IncrementalMetricsWorker worker = newWorker(collector, service);
    MetricDirtyPO dirty = dirty(11L, 4L, System.currentTimeMillis() - 2_000, null, 0);
    BaseMetalake metalake = mock(BaseMetalake.class);
    when(service.listDueDirtyMetalakes(anyLong(), anyLong(), anyLong()))
        .thenReturn(Collections.singletonList(dirty));
    when(service.getDirtyMetalake(11L)).thenReturn(dirty);
    when(collector.metalakeLock(11L)).thenReturn(new Object());
    when(collector.findActiveMetalake(11L)).thenReturn(Optional.of(metalake));
    doThrow(new RuntimeException("calculation failed"))
        .when(collector)
        .collectAndPublish(eq(metalake), eq(MetricsCollector.PublishMode.CURRENT_ONLY), anyLong());
    runSubmittedTasksImmediately(collector);

    worker.pollOnce();

    verify(service)
        .markRetryIfRevision(eq(11L), eq(4L), eq(1), anyLong(), eq("calculation failed"));
    verify(service, never()).deleteDirtyIfRevision(11L, 4L);

    CompletableFuture<Void> pending = new CompletableFuture<>();
    doReturn(pending).when(collector).submitIncremental(any());
    worker.pollOnce();
    worker.pollOnce();
    verify(collector, times(2)).submitIncremental(any());
    pending.complete(null);
    worker.close();
  }

  @Test
  void testInvalidTimingConfigurationIsRejected() {
    MetricsCollector collector = mock(MetricsCollector.class);
    MetricDataService service = mock(MetricDataService.class);
    ServerConfig config = defaultConfig();
    when(config.get(MetricsConfig.INCREMENTAL_DEBOUNCE_MS_CONFIG)).thenReturn(5_000L);
    when(config.get(MetricsConfig.INCREMENTAL_MAX_DEBOUNCE_MS_CONFIG)).thenReturn(1_000L);

    IncrementalMetricsWorker worker = new IncrementalMetricsWorker(collector, service);
    assertThrows(IllegalArgumentException.class, () -> worker.initialize(config));
  }

  @Test
  void testRetryDelayIsCappedAfterJitter() throws Exception {
    MetricsCollector collector = mock(MetricsCollector.class);
    MetricDataService service = mock(MetricDataService.class);
    ServerConfig config = defaultConfig();
    when(config.get(MetricsConfig.INCREMENTAL_RETRY_MAX_MS_CONFIG)).thenReturn(1_000L);
    IncrementalMetricsWorker worker = new IncrementalMetricsWorker(collector, service);
    worker.initialize(config);

    MetricDirtyPO dirty = dirty(12L, 5L, System.currentTimeMillis() - 2_000, null, 3);
    BaseMetalake metalake = mock(BaseMetalake.class);
    when(service.listDueDirtyMetalakes(anyLong(), anyLong(), anyLong()))
        .thenReturn(Collections.singletonList(dirty));
    when(service.getDirtyMetalake(12L)).thenReturn(dirty);
    when(collector.metalakeLock(12L)).thenReturn(new Object());
    when(collector.findActiveMetalake(12L)).thenReturn(Optional.of(metalake));
    doThrow(new RuntimeException("calculation failed"))
        .when(collector)
        .collectAndPublish(eq(metalake), eq(MetricsCollector.PublishMode.CURRENT_ONLY), anyLong());
    runSubmittedTasksImmediately(collector);

    long beforePoll = System.currentTimeMillis();
    worker.pollOnce();
    long afterPoll = System.currentTimeMillis();

    ArgumentCaptor<Long> retryAfter = ArgumentCaptor.forClass(Long.class);
    verify(service)
        .markRetryIfRevision(
            eq(12L), eq(5L), eq(4), retryAfter.capture(), eq("calculation failed"));
    assertTrue(retryAfter.getValue() >= beforePoll + 800L);
    assertTrue(retryAfter.getValue() <= afterPoll + 1_000L);
    worker.close();
  }

  private static IncrementalMetricsWorker newWorker(
      MetricsCollector collector, MetricDataService service) {
    ServerConfig config = defaultConfig();
    IncrementalMetricsWorker worker = new IncrementalMetricsWorker(collector, service);
    worker.initialize(config);
    return worker;
  }

  private static ServerConfig defaultConfig() {
    ServerConfig config = mock(ServerConfig.class);
    when(config.get(MetricsConfig.INCREMENTAL_POLL_INTERVAL_MS_CONFIG)).thenReturn(1_000L);
    when(config.get(MetricsConfig.INCREMENTAL_DEBOUNCE_MS_CONFIG)).thenReturn(1_000L);
    when(config.get(MetricsConfig.INCREMENTAL_MAX_DEBOUNCE_MS_CONFIG)).thenReturn(5_000L);
    when(config.get(MetricsConfig.INCREMENTAL_RETRY_INITIAL_MS_CONFIG)).thenReturn(1_000L);
    when(config.get(MetricsConfig.INCREMENTAL_RETRY_MAX_MS_CONFIG)).thenReturn(60_000L);
    return config;
  }

  private static MetricDirtyPO dirty(
      long metalakeId, long revision, long eventTime, Timestamp retryAfter, int retryCount) {
    MetricDirtyPO dirty = mock(MetricDirtyPO.class);
    when(dirty.getMetalakeId()).thenReturn(metalakeId);
    when(dirty.getRevision()).thenReturn(revision);
    when(dirty.getFirstDirtyAt()).thenReturn(new Timestamp(eventTime));
    when(dirty.getLastEventAt()).thenReturn(new Timestamp(eventTime));
    when(dirty.getRetryAfter()).thenReturn(retryAfter);
    when(dirty.getRetryCount()).thenReturn(retryCount);
    return dirty;
  }

  private static void runSubmittedTasksImmediately(MetricsCollector collector) {
    when(collector.submitIncremental(any()))
        .thenAnswer(
            invocation -> {
              Runnable task = invocation.getArgument(0);
              task.run();
              return CompletableFuture.completedFuture(null);
            });
  }
}
