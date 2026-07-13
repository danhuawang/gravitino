/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datastrato.gravitino.scim.service.adapter.ScimUserRepositoryAdapter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.utils.IsolatedClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies SCIM classes load through {@link IsolatedClassLoader} the same way the auxiliary service
 * does at runtime: isolated jars under {@code scim-server/libs} plus parent delegation to the main
 * Gravitino server classpath.
 */
class TestScimIsolatedClasspath {

  private static final Path SCIM_SERVER_LIBS =
      Path.of(System.getenv("GRAVITINO_HOME"), "distribution/package/scim-server/libs");

  private ClassLoader previousContextClassLoader;

  @BeforeEach
  void setUp() {
    previousContextClassLoader = Thread.currentThread().getContextClassLoader();
  }

  @AfterEach
  void tearDown() {
    Thread.currentThread().setContextClassLoader(previousContextClassLoader);
  }

  @Test
  void testAdapterLoads() throws Exception {
    assumeTrue(Files.isDirectory(SCIM_SERVER_LIBS), "Run copyLibAndConfigs before this test");

    ClassLoader serverLike = createServerLikeClassLoader();
    Thread.currentThread().setContextClassLoader(serverLike);

    IsolatedClassLoader isolated =
        IsolatedClassLoader.buildClassLoader(List.of(SCIM_SERVER_LIBS.toAbsolutePath().toString()));

    isolated.withClassLoader(
        cl -> {
          Class<?> adapterClass = cl.loadClass(ScimUserRepositoryAdapter.class.getName());
          assertNotNull(adapterClass.getClassLoader());
          return null;
        });
  }

  @Test
  void testObjectMapper() throws Exception {
    assumeTrue(Files.isDirectory(SCIM_SERVER_LIBS), "Run copyLibAndConfigs before this test");

    ClassLoader serverLike = createServerLikeClassLoader();
    Thread.currentThread().setContextClassLoader(serverLike);

    IsolatedClassLoader isolated =
        IsolatedClassLoader.buildClassLoader(List.of(SCIM_SERVER_LIBS.toAbsolutePath().toString()));

    assertDoesNotThrow(
        () ->
            isolated.withClassLoader(
                cl -> {
                  Class<?> factoryClass =
                      cl.loadClass("org.apache.directory.scim.core.json.ObjectMapperFactory");
                  Method getObjectMapper = factoryClass.getMethod("getObjectMapper");
                  assertNotNull(getObjectMapper.invoke(null));
                  return null;
                }));
  }

  @Test
  void testFilterParser() throws Exception {
    assumeTrue(Files.isDirectory(SCIM_SERVER_LIBS), "Run copyLibAndConfigs before this test");

    ClassLoader serverLike = createServerLikeClassLoader();
    Thread.currentThread().setContextClassLoader(serverLike);

    IsolatedClassLoader isolated =
        IsolatedClassLoader.buildClassLoader(List.of(SCIM_SERVER_LIBS.toAbsolutePath().toString()));

    assertDoesNotThrow(
        () ->
            isolated.withClassLoader(
                cl -> {
                  Class<?> filterClass =
                      cl.loadClass("org.apache.directory.scim.spec.filter.Filter");
                  Method decode = filterClass.getMethod("decode", String.class);
                  assertNotNull(decode.invoke(null, "externalId eq \"abc\""));
                  return null;
                }));
  }

  /**
   * Simulates the main Gravitino server classpath: everything on the test runtime classpath except
   * jars that belong to the SCIM isolated stack.
   */
  private static ClassLoader createServerLikeClassLoader() {
    ClassLoader testClassLoader = TestScimIsolatedClasspath.class.getClassLoader();
    if (!(testClassLoader instanceof URLClassLoader urlClassLoader)) {
      return testClassLoader;
    }

    List<URL> serverUrls = new ArrayList<>();
    for (URL url : urlClassLoader.getURLs()) {
      String path = url.getFile();
      if (path.contains("scim-service")
          || path.contains("scim-core")
          || path.contains("scim-spec-schema")
          || path.contains("directory.scimple")
          || path.contains("jackson-module-jakarta-xmlbind")
          || path.contains("antlr4-runtime")) {
        continue;
      }
      serverUrls.add(url);
    }
    return new URLClassLoader(serverUrls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }
}
