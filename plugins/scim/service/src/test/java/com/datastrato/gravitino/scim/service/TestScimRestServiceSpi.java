/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.gravitino.Config;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.junit.jupiter.api.Test;

/** Verifies {@link ScimConfig} and {@link ScimRESTService} auxiliary SPI packaging. */
class TestScimRestServiceSpi {

  private static final Path SCIM_SERVER_LIBS =
      Path.of(System.getenv("GRAVITINO_HOME"), "distribution/package/scim-server/libs");

  private static final String SPI_PATH =
      "META-INF/services/org.apache.gravitino.auxiliary.GravitinoAuxiliaryService";

  @Test
  void testScimConfigUsesJettyServerDefaults() {
    Map<String, String> properties = ImmutableMap.of();
    ScimConfig scimConfig = new ScimConfig(properties, new Config() {});
    JettyServerConfig serverConfig = JettyServerConfig.fromConfig(scimConfig);

    assertEquals(ScimConfig.DEFAULT_HTTP_PORT, serverConfig.getHttpPort());
    assertEquals("0.0.0.0", serverConfig.getHost());
  }

  @Test
  void testAuxiliaryServiceSpiPackagedInScimServiceJar() throws Exception {
    assumeTrue(Files.isDirectory(SCIM_SERVER_LIBS), "Run copyLibAndConfigs before this test");

    Path scimServiceJar = findScimServiceJar();
    assertNotNull(scimServiceJar, "gravitino-scim-service jar should be packaged");

    try (JarFile jarFile = new JarFile(scimServiceJar.toFile())) {
      assertNotNull(jarFile.getEntry(SPI_PATH), "SPI descriptor should be present");

      String providerLines;
      try (var inputStream = jarFile.getInputStream(jarFile.getEntry(SPI_PATH));
          var reader =
              new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        providerLines = reader.lines().collect(Collectors.joining("\n"));
      }

      assertTrue(
          providerLines.contains(ScimRESTService.class.getName()),
          "SPI should register ScimRESTService");
    }
  }

  @Test
  void testJetty11PackagedForIsolatedClasspath() throws Exception {
    assumeTrue(Files.isDirectory(SCIM_SERVER_LIBS), "Run copyLibAndConfigs before this test");

    try (Stream<Path> jars = Files.list(SCIM_SERVER_LIBS)) {
      boolean hasJetty11 =
          jars.anyMatch(
              path -> {
                String name = path.getFileName().toString();
                return name.startsWith("jetty-server-")
                    || name.startsWith("jetty-servlet-")
                    || name.startsWith("jetty-http-");
              });
      assertTrue(hasJetty11, "scim-server/libs should include Jetty 11 jars for isolated startup");
    }
  }

  private static Path findScimServiceJar() throws Exception {
    try (Stream<Path> jars = Files.list(SCIM_SERVER_LIBS)) {
      return jars.filter(path -> path.getFileName().toString().startsWith("gravitino-scim-service"))
          .findFirst()
          .orElse(null);
    }
  }
}
