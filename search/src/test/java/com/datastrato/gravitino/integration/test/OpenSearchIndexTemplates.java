/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.integration.test;

import com.datastrato.gravitino.test.OpenSearchContainer;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the OpenSearch index templates an integration test needs before the Gravitino server
 * starts.
 *
 * <p>The server refuses to start when an entity type it indexes has no template, so a test that
 * configures the OpenSearch search storage has to provision the bundle first. The templates are
 * created with the script the distribution ships, which is the same path an operator runs.
 */
final class OpenSearchIndexTemplates {

  private static final Logger LOG = LoggerFactory.getLogger(OpenSearchIndexTemplates.class);

  /**
   * The bundle holding a template for every entity type the server indexes. The view and tag
   * templates only exist from v2 on, and {@code OpenSearchStorage#checkIndexTemplate} refuses to
   * start the server when an indexed entity type has no template.
   */
  static final String CURRENT_VERSION = "v2";

  private OpenSearchIndexTemplates() {}

  /**
   * Creates every index template of the given bundle version.
   *
   * @param container The OpenSearch instance to provision.
   * @param version The template bundle version, for instance {@code v2}.
   */
  static void create(OpenSearchContainer container, String version) {
    try {
      Path scriptPath = resolveScriptPath(Paths.get(System.getProperty("user.dir")));
      File workDir = scriptPath.getParent().getParent().toFile();

      Process process =
          new ProcessBuilder(
                  "/bin/bash",
                  scriptPath.toString(),
                  version,
                  container.getOpenSearchUrl(),
                  OpenSearchContainer.DEFAULT_USERNAME,
                  OpenSearchContainer.DEFAULT_PASSWORD)
              .directory(workDir)
              .redirectErrorStream(true)
              .start();

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
        }
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new RuntimeException(
            "Failed to initialize OpenSearch index templates, exitCode="
                + exitCode
                + ", output="
                + output);
      }
      LOG.info("Initialized OpenSearch index templates successfully: {}", output);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize OpenSearch index templates", e);
    }
  }

  private static Path resolveScriptPath(Path userDir) {
    Path current = userDir;
    while (current != null) {
      Path candidate = current.resolve("bin/opensearch/create_indices_template.sh.template");
      if (Files.exists(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }

    throw new RuntimeException(
        "Cannot find create_indices_template.sh.template from user.dir=" + userDir);
  }
}
