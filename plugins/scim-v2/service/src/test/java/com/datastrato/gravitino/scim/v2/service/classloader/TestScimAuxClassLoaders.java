/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.classloader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.v2.service.classloader.ScimAuxClassLoaders.ScimChildFirstClassLoader;
import org.junit.jupiter.api.Test;

/** Unit tests for SCIM child-first bridge delegation rules. */
class TestScimAuxClassLoaders {

  @Test
  void testDelegatesGravitinoCoreAndScimExtension() {
    assertTrue(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "org.apache.gravitino.GravitinoEnv"));
    assertTrue(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.v2.ScimTokenManager"));
    assertTrue(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.v2.web.rest.feature.ScimTokenRESTFeature"));
  }

  @Test
  void testKeepsScimServiceStackIsolated() {
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.v2.service.ScimRESTServiceImpl"));
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.v2.service.ScimJettyServer"));
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "org.glassfish.jersey.servlet.ServletContainer"));
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "org.eclipse.jetty.server.Server"));
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "jakarta.servlet.http.HttpServlet"));
  }
}
