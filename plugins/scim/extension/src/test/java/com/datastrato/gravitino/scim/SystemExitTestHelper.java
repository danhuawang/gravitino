/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

/** Test-only helpers for code paths that call {@link System#exit(int)}. */
public final class SystemExitTestHelper {

  private SystemExitTestHelper() {}

  /**
   * Runs an action while intercepting {@link System#exit(int)} and rethrowing it as {@link
   * SystemExitException}.
   *
   * @param action the action that may call {@code System.exit}
   */
  @SuppressWarnings("removal")
  public static void runWithExitGuard(Runnable action) {
    SecurityManager original = System.getSecurityManager();
    System.setSecurityManager(
        new SecurityManager() {
          @Override
          public void checkExit(int status) {
            throw new SystemExitException(status);
          }

          @Override
          public void checkPermission(java.security.Permission perm) {
            // Allow test execution.
          }
        });
    try {
      action.run();
    } finally {
      System.setSecurityManager(original);
    }
  }

  /** Thrown by {@link #runWithExitGuard(Runnable)} when the guarded action calls {@code exit}. */
  public static final class SystemExitException extends SecurityException {
    private final int status;

    private SystemExitException(int status) {
      super("System.exit(" + status + ")");
      this.status = status;
    }

    /** Returns the exit status passed to {@link System#exit(int)}. */
    public int status() {
      return status;
    }
  }
}
