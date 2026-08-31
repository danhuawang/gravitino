/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import java.lang.reflect.Constructor;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.storage.IdGenerator;

/** Test-only helpers for {@link ScimTokenManager}. */
public final class ScimTokenManagerTestHelper {

  private ScimTokenManagerTestHelper() {}

  /**
   * Creates an isolated manager instance without using the process-wide singleton.
   *
   * @param config the server configuration
   * @param entityStore the entity store for metalake resolution
   * @param idGenerator the id generator
   * @return a new manager instance
   */
  public static ScimTokenManager newManager(
      Config config, EntityStore entityStore, IdGenerator idGenerator)
      throws ReflectiveOperationException {
    Constructor<ScimTokenManager> constructor =
        ScimTokenManager.class.getDeclaredConstructor(
            Config.class, EntityStore.class, IdGenerator.class);
    constructor.setAccessible(true);
    return constructor.newInstance(config, entityStore, idGenerator);
  }
}
