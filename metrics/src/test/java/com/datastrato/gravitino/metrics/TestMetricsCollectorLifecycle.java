/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.metrics.config.MetricsConfig;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.server.ServerConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TestMetricsCollectorLifecycle {

  @Test
  void testCloseTerminatesExecutorsAndRejectsNewTasks() throws Exception {
    MetricsCollector collector = newInitializedCollector();
    ThreadPoolExecutor scheduler = executor(collector, "scheduledExecutor");
    ThreadPoolExecutor calculation = executor(collector, "metricsCalculationExecutor");
    ThreadPoolExecutor authorization = executor(collector, "metricsAuthorizationExecutor");
    try {
      CompletableFuture<Void> calculationTask = collector.submitIncremental(() -> {});
      CompletableFuture<Void> authorizationTask =
          CompletableFuture.runAsync(() -> {}, authorization);

      collector.close();

      CompletableFuture.allOf(calculationTask, authorizationTask).get(5, TimeUnit.SECONDS);
      assertTrue(scheduler.isTerminated());
      assertTrue(calculation.isTerminated());
      assertTrue(authorization.isTerminated());
      assertThrows(RejectedExecutionException.class, () -> collector.submitIncremental(() -> {}));
      assertThrows(
          RejectedExecutionException.class,
          () -> CompletableFuture.runAsync(() -> {}, authorization));
    } finally {
      scheduler.shutdownNow();
      calculation.shutdownNow();
      authorization.shutdownNow();
    }
  }

  @Test
  void testClosePreservesQueuedTasksAfterTerminationTimeout() throws Exception {
    assertClosePreservesQueuedTasks(null);
  }

  @Test
  void testClosePreservesQueuedTasksWhenSchedulerWaitIsInterrupted() throws Exception {
    assertClosePreservesQueuedTasks("scheduledExecutor");
  }

  @Test
  void testClosePreservesQueuedTasksWhenCalculationWaitIsInterrupted() throws Exception {
    assertClosePreservesQueuedTasks("metricsCalculationExecutor");
  }

  @Test
  void testClosePreservesQueuedTasksWhenAuthorizationWaitIsInterrupted() throws Exception {
    assertClosePreservesQueuedTasks("metricsAuthorizationExecutor");
  }

  private static void assertClosePreservesQueuedTasks(@Nullable String interruptedExecutor)
      throws Exception {
    MetricsCollector collector = newCollector();
    ScheduledThreadPoolExecutor scheduler = mock(ScheduledThreadPoolExecutor.class);
    if ("scheduledExecutor".equals(interruptedExecutor)) {
      when(scheduler.awaitTermination(30, TimeUnit.SECONDS))
          .thenThrow(new InterruptedException("Interrupted while closing scheduler"));
    }
    ThreadPoolExecutor calculation =
        controlledExecutor("metricsCalculationExecutor".equals(interruptedExecutor));
    ThreadPoolExecutor authorization =
        controlledExecutor("metricsAuthorizationExecutor".equals(interruptedExecutor));
    CountDownLatch tasksStarted = new CountDownLatch(2);
    CountDownLatch releaseTasks = new CountDownLatch(1);
    try {
      setField(collector, "scheduledExecutor", scheduler);
      setField(collector, "metricsCalculationExecutor", calculation);
      setField(collector, "metricsAuthorizationExecutor", authorization);
      Runnable blockingTask =
          () -> {
            tasksStarted.countDown();
            try {
              releaseTasks.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError("Accepted task was interrupted during close", e);
            }
          };
      CompletableFuture<Void> runningCalculation = collector.submitIncremental(blockingTask);
      CompletableFuture<Void> runningAuthorization =
          CompletableFuture.runAsync(blockingTask, authorization);
      assertTrue(tasksStarted.await(5, TimeUnit.SECONDS));
      CompletableFuture<Void> queuedCalculation = collector.submitIncremental(() -> {});
      CompletableFuture<Void> queuedAuthorization =
          CompletableFuture.runAsync(() -> {}, authorization);

      collector.close();

      if (interruptedExecutor != null) {
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
      }
      verify(scheduler).shutdown();
      verify(scheduler).shutdownNow();
      assertTrue(calculation.isShutdown());
      assertTrue(authorization.isShutdown());
      assertFalse(queuedCalculation.isDone());
      assertFalse(queuedAuthorization.isDone());
      releaseTasks.countDown();
      CompletableFuture.allOf(
              runningCalculation, runningAuthorization, queuedCalculation, queuedAuthorization)
          .get(5, TimeUnit.SECONDS);
    } finally {
      Thread.interrupted();
      releaseTasks.countDown();
      calculation.shutdownNow();
      authorization.shutdownNow();
    }
  }

  private static ThreadPoolExecutor controlledExecutor(boolean interruptWaiting) {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        task -> {
          Thread thread = new Thread(task, "metrics-close-test");
          thread.setDaemon(true);
          return thread;
        }) {
      /** {@inheritDoc} */
      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (interruptWaiting) {
          throw new InterruptedException("Interrupted while awaiting executor termination");
        }
        // Simulate an expired wait while real accepted tasks remain active and queued.
        return false;
      }
    };
  }

  private static MetricsCollector newInitializedCollector() throws Exception {
    MetricsCollector collector = newCollector();
    ServerConfig config = mock(ServerConfig.class);
    when(config.get(MetricsConfig.RETENTION_DAYS_CONFIG)).thenReturn(30);
    when(config.get(MetricsConfig.AUTHORIZATION_CALCULATION_THREADS_CONFIG)).thenReturn(1);
    when(config.get(Configs.ENABLE_AUTHORIZATION)).thenReturn(true);
    MetricDataService service = mock(MetricDataService.class);
    try (MockedStatic<MetricDataService> mockedService =
        Mockito.mockStatic(MetricDataService.class)) {
      mockedService.when(MetricDataService::getInstance).thenReturn(service);
      collector.initialize(config, mock(GravitinoEnv.class));
    }
    return collector;
  }

  private static MetricsCollector newCollector() throws ReflectiveOperationException {
    Constructor<MetricsCollector> constructor = MetricsCollector.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    return constructor.newInstance();
  }

  private static ThreadPoolExecutor executor(MetricsCollector collector, String fieldName)
      throws ReflectiveOperationException {
    Field field = MetricsCollector.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (ThreadPoolExecutor) field.get(collector);
  }

  private static void setField(MetricsCollector collector, String fieldName, Object value)
      throws ReflectiveOperationException {
    Field field = MetricsCollector.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(collector, value);
  }
}
