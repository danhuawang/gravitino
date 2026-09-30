/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.server.web;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/**
 * Records out-of-memory failures observed by the server. Once recorded, the failure remains until
 * the process restarts; a successful request does not establish that the JVM has recovered.
 *
 * <p>The failure path only sets a flag. It does not retain the error, format its stack trace, or
 * allocate a collection while walking its causes.
 */
public final class ServerHealth {
  private static final ServerHealth INSTANCE = new ServerHealth();

  private volatile boolean outOfMemory;
  private final Map<String, BooleanSupplier> optionalChecks = new ConcurrentHashMap<>();

  /** Creates an independent health state, initially healthy. */
  public ServerHealth() {}

  /**
   * Returns the shared state used by the server's request and health-check paths.
   *
   * @return the shared health state
   */
  public static ServerHealth getInstance() {
    return INSTANCE;
  }

  /**
   * Records an out-of-memory error, including one wrapped in another throwable. Suppressed
   * exceptions are not inspected, avoiding the array copies made by {@link
   * Throwable#getSuppressed()}.
   *
   * @param failure the observed failure, or null
   */
  public void recordFailure(@Nullable Throwable failure) {
    Throwable slow = failure;
    boolean advanceSlow = false;
    while (failure != null) {
      if (failure instanceof OutOfMemoryError) {
        outOfMemory = true;
        return;
      }
      failure = failure.getCause();
      if (advanceSlow) {
        slow = slow.getCause();
      }
      advanceSlow = !advanceSlow;
      // Throwable cause chains can be cyclic. Detect cycles without allocating a visited set.
      if (failure == slow) {
        return;
      }
    }
  }

  /**
   * Returns whether this server has observed an out-of-memory error.
   *
   * @return true after an out-of-memory error has been recorded
   */
  public boolean hasOutOfMemoryError() {
    return outOfMemory;
  }

  /**
   * Registers an optional component that is reported by the health endpoints without affecting the
   * overall status, so an unavailable optional component does not take the server out of service.
   *
   * @param name the component name shown in the health response
   * @param check returns whether the component is available
   */
  public void registerOptionalCheck(String name, BooleanSupplier check) {
    optionalChecks.put(name, check);
  }

  /**
   * Removes an optional component check.
   *
   * @param name the component name
   */
  public void removeOptionalCheck(String name) {
    optionalChecks.remove(name);
  }

  /**
   * Returns a snapshot of the optional component checks, ordered by name.
   *
   * @return an immutable snapshot of registered checks
   */
  public Map<String, BooleanSupplier> optionalChecks() {
    return Collections.unmodifiableMap(new TreeMap<>(optionalChecks));
  }
}
