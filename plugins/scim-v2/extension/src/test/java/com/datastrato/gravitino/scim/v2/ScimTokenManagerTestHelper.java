/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2;

import java.lang.reflect.Constructor;
import org.apache.gravitino.Config;
import org.apache.gravitino.storage.IdGenerator;

/** Test-only helpers for {@link ScimTokenManager}. */
public final class ScimTokenManagerTestHelper {

  private ScimTokenManagerTestHelper() {}

  /**
   * Creates an isolated manager instance without using the process-wide singleton.
   *
   * @param config the server configuration
   * @param idGenerator the id generator
   * @return a new manager instance
   */
  public static ScimTokenManager newManager(Config config, IdGenerator idGenerator)
      throws ReflectiveOperationException {
    Constructor<ScimTokenManager> constructor =
        ScimTokenManager.class.getDeclaredConstructor(Config.class, IdGenerator.class);
    constructor.setAccessible(true);
    return constructor.newInstance(config, idGenerator);
  }
}
