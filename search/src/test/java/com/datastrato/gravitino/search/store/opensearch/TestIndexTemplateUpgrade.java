/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_PASSWORD;
import static com.datastrato.gravitino.test.OpenSearchContainer.DEFAULT_USERNAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.test.OpenSearchContainer;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the operator-facing upgrade path of the OpenSearch index templates.
 *
 * <p>A release may add a template for a new entity type without changing the mappings of the
 * existing ones. {@code OpenSearchStorage#checkIndexTemplate} refuses to start when any template is
 * missing, so {@code index.sh} has to be able to fill in the missing ones on a cluster that is
 * already on the same template version.
 */
@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestIndexTemplateUpgrade {

  private static final String VERSION = "v1";
  /** Stands in for the template of an entity type that a later release adds to this version. */
  private static final String ADDED_TEMPLATE = "model_entity_index_template_" + VERSION;

  private static final String EXISTING_TEMPLATE = "table_entity_index_template_" + VERSION;

  private OpenSearchContainer container;

  @TempDir private Path binDir;

  @BeforeAll
  public void startContainer() {
    Assumptions.assumeTrue(isJqAvailable(), "index.sh requires jq");
    this.container =
        new OpenSearchContainer(
            "ci-opensearch-upgrade",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                DEFAULT_PASSWORD));
    container.start();
  }

  @AfterAll
  public void stopContainer() {
    if (container != null) {
      container.stop();
    }
  }

  @BeforeEach
  void resetCluster() throws Exception {
    // The container is shared by the whole class, every test starts from an unprovisioned cluster.
    curl(
        "-X",
        "DELETE",
        container.getOpenSearchUrl() + "/_index_template/*_entity_index_template_" + VERSION);
    stageBinDirectory();
  }

  @Test
  void testUpgradeFillsInATemplateAddedToAnExistingVersion() throws Exception {

    assertEquals(0, runIndexScript("init"), "init should create every template");
    assertTemplateExists(ADDED_TEMPLATE);
    assertTemplateExists(EXISTING_TEMPLATE);

    // Simulate a cluster provisioned before this entity type's template was added to v1.
    deleteTemplate(ADDED_TEMPLATE);
    assertTemplateMissing(ADDED_TEMPLATE);

    // init refuses to touch a provisioned cluster, upgrade is the supported path.
    assertNotEquals(0, runIndexScript("init"), "init must not run against a provisioned cluster");
    assertTemplateMissing(ADDED_TEMPLATE);

    assertEquals(0, runIndexScript("upgrade", VERSION));

    assertTemplateExists(ADDED_TEMPLATE);
    // Reconciling must not disturb the templates that were already in place.
    assertTemplateExists(EXISTING_TEMPLATE);
  }

  @Test
  void testUpgradeIsIdempotentWhenNothingIsMissing() throws Exception {
    assertEquals(0, runIndexScript("init"));
    assertEquals(0, runIndexScript("upgrade", VERSION));
    assertEquals(0, runIndexScript("upgrade", VERSION));

    assertTemplateExists(ADDED_TEMPLATE);
    assertTemplateExists(EXISTING_TEMPLATE);
  }

  /** Copies the scripts under {@code bin/} into the temporary directory, dropping the suffix. */
  private void stageBinDirectory() throws IOException {
    Path source = Paths.get(System.getProperty("user.dir"), "..", "bin").normalize();
    Path openSearchDir = binDir.resolve("opensearch");
    Files.createDirectories(openSearchDir.resolve(VERSION));

    copyScript(source.resolve("index.sh.template"), binDir.resolve("index.sh"));
    for (String script : new String[] {"create_indices_template", "delete_indices_template"}) {
      copyScript(
          source.resolve("opensearch").resolve(script + ".sh.template"),
          openSearchDir.resolve(script + ".sh"));
    }

    try (Stream<Path> templates = Files.list(source.resolve("opensearch").resolve(VERSION))) {
      for (Path template : templates.toArray(Path[]::new)) {
        Files.copy(
            template,
            openSearchDir.resolve(VERSION).resolve(template.getFileName().toString()),
            StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private void copyScript(Path from, Path to) throws IOException {
    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    assertTrue(to.toFile().setExecutable(true));
  }

  private int runIndexScript(String... arguments) throws Exception {
    String[] command = new String[arguments.length + 4];
    command[0] = "/bin/bash";
    command[1] = binDir.resolve("index.sh").toString();
    System.arraycopy(arguments, 0, command, 2, arguments.length);
    command[arguments.length + 2] = "--opensearch_uri=" + container.getOpenSearchUrl();
    command[arguments.length + 3] = "--username=" + DEFAULT_USERNAME;

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.command().add("--password=" + DEFAULT_PASSWORD);
    builder.directory(binDir.toFile());
    // The script reads the server configuration when GRAVITINO_HOME points at an install.
    builder.environment().remove("GRAVITINO_HOME");
    builder.redirectErrorStream(true);

    Process process = builder.start();
    String output = readFully(process.getInputStream());
    int exitCode = process.waitFor();
    OpenSearchContainer.LOG.info("index.sh {} exited with {}:\n{}", arguments[0], exitCode, output);
    return exitCode;
  }

  private void assertTemplateExists(String template) throws Exception {
    assertEquals(200, templateStatus(template), template + " should exist");
  }

  private void assertTemplateMissing(String template) throws Exception {
    assertEquals(404, templateStatus(template), template + " should be absent");
  }

  private void deleteTemplate(String template) throws Exception {
    assertEquals(200, curl("-X", "DELETE", templateUrl(template)));
  }

  private int templateStatus(String template) throws Exception {
    return curl("-X", "GET", templateUrl(template));
  }

  private String templateUrl(String template) {
    return container.getOpenSearchUrl() + "/_index_template/" + template;
  }

  private int curl(String... arguments) throws Exception {
    String[] command =
        Stream.concat(
                Stream.of(
                    "curl",
                    "-s",
                    "-k",
                    "-o",
                    "/dev/null",
                    "-w",
                    "%{http_code}",
                    "-u",
                    DEFAULT_USERNAME + ":" + DEFAULT_PASSWORD),
                Stream.of(arguments))
            .toArray(String[]::new);
    Process process = new ProcessBuilder(command).start();
    String status = readFully(process.getInputStream()).trim();
    process.waitFor();
    return Integer.parseInt(status);
  }

  private static String readFully(InputStream stream) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int read;
    while ((read = stream.read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    return buffer.toString(StandardCharsets.UTF_8.name());
  }

  private static boolean isJqAvailable() {
    try {
      return new ProcessBuilder("jq", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
