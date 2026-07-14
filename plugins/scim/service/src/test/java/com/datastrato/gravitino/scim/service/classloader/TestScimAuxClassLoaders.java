/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.classloader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.service.classloader.ScimAuxClassLoaders.ScimChildFirstClassLoader;
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
            "com.datastrato.gravitino.scim.ScimTokenManager"));
    assertTrue(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.web.rest.feature.ScimTokenRESTFeature"));
  }

  @Test
  void testKeepsScimServiceStackIsolated() {
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.service.ScimRESTServiceImpl"));
    assertFalse(
        ScimChildFirstClassLoader.shouldDelegateToGravitinoBridge(
            "com.datastrato.gravitino.scim.service.ScimJettyServer"));
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
