/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceConfigurationError;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestMetricsCollectionFailure {
  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void testCatalogFailuresAreRecoverable() {
    for (Throwable failure :
        new Throwable[] {
          new IllegalStateException("unavailable"),
          new NoClassDefFoundError("provider dependency"),
          new ServiceConfigurationError("provider configuration"),
          new AssertionError("provider assertion")
        }) {
      assertDoesNotThrow(() -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(failure));
    }
  }

  @Test
  void testDirectAndWrappedFatalErrorsAreRethrown() {
    for (Error fatal :
        new Error[] {
          new OutOfMemoryError("test"),
          new StackOverflowError("test"),
          new InternalError("test"),
          new UnknownError("test"),
          new ThreadDeath()
        }) {
      assertSame(
          fatal,
          assertThrows(
              fatal.getClass(), () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(fatal)));
      Throwable wrapped = new ServiceConfigurationError("provider constructor", fatal);
      assertSame(
          fatal,
          assertThrows(
              fatal.getClass(), () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(wrapped)));
    }
  }

  @Test
  void testWrappedInterruptionRestoresInterruptFlag() {
    Throwable wrapped = new RuntimeException(new InterruptedException("stopping"));
    CancellationException failure =
        assertThrows(
            CancellationException.class,
            () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(wrapped));
    assertSame(wrapped, failure.getCause());
    assertTrue(Thread.currentThread().isInterrupted());
  }

  @Test
  void testExistingInterruptFlagAbortsRecovery() {
    Thread.currentThread().interrupt();
    assertThrows(
        CancellationException.class,
        () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(new NoClassDefFoundError()));
    assertTrue(Thread.currentThread().isInterrupted());
  }

  @Test
  void testCancellationKeepsItsIdentityAcrossInterruptedTaskBoundaries() {
    CancellationException cancelled = new CancellationException("cancelled");
    cancelled.initCause(new InterruptedException("stopping"));
    assertSame(
        cancelled,
        assertThrows(
            CancellationException.class,
            () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(cancelled)));
    assertTrue(Thread.currentThread().isInterrupted());
  }

  @Test
  void testWrappedCancellationDoesNotInterruptThread() {
    CancellationException cancelled = new CancellationException("cancelled");
    assertSame(
        cancelled,
        assertThrows(
            CancellationException.class,
            () ->
                MetricsCollectionFailure.rethrowIfFatalOrCancelled(
                    new RuntimeException(cancelled))));
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  void testFatalCauseTakesPrecedenceOverCancellation() {
    Error fatal = new OutOfMemoryError("test");
    CancellationException cancelled = new CancellationException("cancelled");
    cancelled.initCause(fatal);
    assertSame(
        fatal,
        assertThrows(
            OutOfMemoryError.class,
            () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(cancelled)));
  }

  @Test
  void testCyclicCauseChainTerminatesAndStillFindsFatalErrors() {
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalStateException("second", first);
    first.initCause(second);
    assertDoesNotThrow(() -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(first));

    Error fatal = new InternalError("test");
    Throwable wrapped = new ServiceConfigurationError("provider", fatal);
    fatal.initCause(wrapped);
    assertSame(
        fatal,
        assertThrows(
            InternalError.class,
            () -> MetricsCollectionFailure.rethrowIfFatalOrCancelled(wrapped)));
  }
}
