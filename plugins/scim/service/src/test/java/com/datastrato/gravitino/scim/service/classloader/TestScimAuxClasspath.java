/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service.classloader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datastrato.gravitino.scim.service.classloader.ScimAuxClassLoaders.ScimChildFirstClassLoader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the SCIM HTTP stack loads through {@link ScimAuxClassLoaders} the same way {@code
 * ScimRESTService} bootstraps at runtime: child-first jars under {@code scim-server/libs}, with
 * Gravitino / SCIM extension types delegated to the bridge loader.
 */
class TestScimAuxClasspath {

  private static final String IMPL_CLASS =
      "com.datastrato.gravitino.scim.service.ScimRESTServiceImpl";
  private static final String JETTY_SERVER = "org.eclipse.jetty.server.Server";
  private static final String JERSEY_SERVLET = "org.glassfish.jersey.servlet.ServletContainer";

  private ClassLoader previousContextClassLoader;
  private Path scimServerLibs;

  @BeforeEach
  void setUp() {
    previousContextClassLoader = Thread.currentThread().getContextClassLoader();
    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    assumeTrue(gravitinoHome != null && !gravitinoHome.isBlank(), "GRAVITINO_HOME must be set");
    scimServerLibs = Path.of(gravitinoHome, "distribution/package/scim-server/libs");
    assumeTrue(Files.isDirectory(scimServerLibs), "Run copyLibAndConfigs before this test");
  }

  @AfterEach
  void tearDown() {
    Thread.currentThread().setContextClassLoader(previousContextClassLoader);
  }

  @Test
  void testImplAndHttpStackLoadFromChildFirstLoader() throws Exception {
    ClassLoader bridge = createServerLikeClassLoader();
    Thread.currentThread().setContextClassLoader(bridge);

    try (URLClassLoader child =
        ScimAuxClassLoaders.create(bridge, scimServerLibs.toAbsolutePath().toString())) {
      assertTrue(child instanceof ScimChildFirstClassLoader);

      Class<?> implClass = child.loadClass(IMPL_CLASS);
      assertSame(child, implClass.getClassLoader());

      Class<?> jettyClass = child.loadClass(JETTY_SERVER);
      assertSame(child, jettyClass.getClassLoader());

      Class<?> jerseyServlet = child.loadClass(JERSEY_SERVLET);
      assertSame(child, jerseyServlet.getClassLoader());

      // When Jetty is also visible on the bridge (IT / server classpath), it must be a different
      // Class object than the Jetty 11 type from scim-server/libs.
      try {
        Class<?> bridgeJetty = bridge.loadClass(JETTY_SERVER);
        assertNotSame(bridgeJetty, jettyClass);
      } catch (ClassNotFoundException ignored) {
        // Bridge without Jetty is fine; child still owns the HTTP stack.
      }
    }
  }

  @Test
  void testObjectMapperFromSciMpleLibs() throws Exception {
    ClassLoader bridge = createServerLikeClassLoader();
    Thread.currentThread().setContextClassLoader(bridge);

    try (URLClassLoader child =
        ScimAuxClassLoaders.create(bridge, scimServerLibs.toAbsolutePath().toString())) {
      assertDoesNotThrow(
          () -> {
            Class<?> factoryClass =
                child.loadClass("org.apache.directory.scim.core.json.ObjectMapperFactory");
            Method getObjectMapper = factoryClass.getMethod("getObjectMapper");
            assertNotNull(getObjectMapper.invoke(null));
          });
    }
  }

  @Test
  void testFilterParserFromSciMpleLibs() throws Exception {
    ClassLoader bridge = createServerLikeClassLoader();
    Thread.currentThread().setContextClassLoader(bridge);

    try (URLClassLoader child =
        ScimAuxClassLoaders.create(bridge, scimServerLibs.toAbsolutePath().toString())) {
      assertDoesNotThrow(
          () -> {
            Class<?> filterClass = child.loadClass("org.apache.directory.scim.spec.filter.Filter");
            Method decode = filterClass.getMethod("decode", String.class);
            assertNotNull(decode.invoke(null, "externalId eq \"abc\""));
          });
    }
  }

  /**
   * Simulates the main Gravitino server classpath: everything on the test runtime classpath except
   * jars that belong to the SCIM isolated stack.
   */
  private static ClassLoader createServerLikeClassLoader() {
    ClassLoader testClassLoader = TestScimAuxClasspath.class.getClassLoader();
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
