/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.integration.test;

import java.util.Map;
import org.apache.gravitino.auxiliary.GravitinoAuxiliaryService;

/**
 * Minimal SCIM auxiliary service placeholder for {@link ScimTokenRESTApiIT}.
 *
 * <p>Token admin REST tests run on the main Jersey 2 server and only require {@code scim} to appear
 * in {@code gravitino.auxService.names}. Starting the real Jersey 3 SCIM listener in-process would
 * conflict with the MiniGravitino classpath, so this stub satisfies aux registration instead.
 */
public class ScimPlaceholderAuxService implements GravitinoAuxiliaryService {

  /** {@inheritDoc} */
  @Override
  public String shortName() {
    return "scim";
  }

  /** {@inheritDoc} */
  @Override
  public void serviceInit(Map<String, String> properties, boolean auxMode) {
    // No-op placeholder.
  }

  /** {@inheritDoc} */
  @Override
  public void serviceStart() {
    // No-op placeholder.
  }

  /** {@inheritDoc} */
  @Override
  public void serviceStop() throws Exception {
    // No-op placeholder.
  }
}
