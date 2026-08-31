/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import java.lang.reflect.Constructor;
import org.apache.gravitino.Config;

/** Test-only helpers for {@link ScimUserGroupRelManager}. */
public final class ScimUserGroupRelManagerTestHelper {

  private ScimUserGroupRelManagerTestHelper() {}

  /**
   * Creates an isolated manager instance without using the process-wide singleton.
   *
   * @param config the server configuration
   * @return a new manager instance
   */
  public static ScimUserGroupRelManager newManager(Config config)
      throws ReflectiveOperationException {
    Constructor<ScimUserGroupRelManager> constructor =
        ScimUserGroupRelManager.class.getDeclaredConstructor(Config.class);
    constructor.setAccessible(true);
    return constructor.newInstance(config);
  }
}
