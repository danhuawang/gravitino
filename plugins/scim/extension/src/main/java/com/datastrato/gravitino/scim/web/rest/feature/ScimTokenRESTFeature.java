/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.web.rest.feature;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.web.rest.ScimTokenBinder;
import com.datastrato.gravitino.scim.web.rest.ScimTokenOperations;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;
import org.apache.gravitino.GravitinoEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers SCIM token admin REST resources on the main Gravitino server.
 *
 * <p>Configure {@code gravitino.server.rest.extensionPackages} to include {@link
 * #SCIM_TOKEN_REST_EXTENSION_PACKAGE} so Jersey auto-discovers this feature.
 */
@Provider
public class ScimTokenRESTFeature implements Feature {

  private static final Logger LOG = LoggerFactory.getLogger(ScimTokenRESTFeature.class);

  /** Extension package name registered through {@code gravitino.server.rest.extensionPackages}. */
  public static final String SCIM_TOKEN_REST_EXTENSION_PACKAGE =
      ScimTokenRESTFeature.class.getPackageName();

  @Override
  public boolean configure(FeatureContext context) {
    GravitinoEnv env = GravitinoEnv.getInstance();
    ScimTokenManager.getInstance().initialize(env.config(), env.entityStore(), env.idGenerator());
    LOG.info("Initialized SCIM token manager for token admin REST APIs");

    context.register(ScimTokenBinder.class);
    context.register(ScimTokenOperations.class);
    return true;
  }
}
