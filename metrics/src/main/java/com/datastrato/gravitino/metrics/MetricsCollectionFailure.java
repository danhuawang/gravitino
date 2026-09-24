/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import java.util.concurrent.CancellationException;

/** Failure handling shared by catalog collection and its task boundaries. */
final class MetricsCollectionFailure {
  private MetricsCollectionFailure() {}

  static void rethrowIfFatalOrCancelled(Throwable failure) {
    Throwable cause = failure;
    Throwable slow = failure;
    boolean advanceSlow = false;
    boolean interrupted = false;
    CancellationException cancellation = null;
    // Providers and reflective loaders can wrap fatal errors. Walk without allocating a set,
    // and detect cycles so malformed provider exceptions cannot hang the collection thread.
    while (cause != null) {
      if (cause instanceof VirtualMachineError) {
        throw (VirtualMachineError) cause;
      }
      if (cause instanceof ThreadDeath) {
        throw (ThreadDeath) cause;
      }
      if (cause instanceof InterruptedException) {
        interrupted = true;
      }
      if (cause instanceof CancellationException) {
        cancellation = (CancellationException) cause;
      }
      cause = cause.getCause();
      if (advanceSlow) {
        slow = slow.getCause();
      }
      advanceSlow = !advanceSlow;
      if (cause == slow) {
        break;
      }
    }

    if (interrupted || Thread.currentThread().isInterrupted()) {
      Thread.currentThread().interrupt();
      if (cancellation != null) {
        throw cancellation;
      }
      CancellationException aborted =
          new CancellationException("Dashboard metric collection was interrupted");
      aborted.initCause(failure);
      throw aborted;
    }
    if (cancellation != null) {
      throw cancellation;
    }
  }
}
