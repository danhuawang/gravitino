/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.test;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.containers.output.OutputFrame.OutputType.END;

import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.BaseConsumer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class OpenSearchContainer extends GenericContainer<OpenSearchContainer> {
  public static final Logger LOG = LoggerFactory.getLogger(OpenSearchContainer.class);

  public static final String DEFAULT_IMAGE = "datastratosandbox/opensearch-with-analysis-ik:2.17.1";
  public static final int PORT = 9200;

  public static final String DEFAULT_USERNAME = "admin";
  public static final String DEFAULT_PASSWORD = "axzin1S3?@A";

  public OpenSearchContainer(String hostName, Map<String, String> envVars) {
    super(DockerImageName.parse(DEFAULT_IMAGE));
    withLogConsumer(new PrintingContainerLog(format("%-15s| ", "Opensearch-Container")));
    withExposedPorts(PORT);
    // Tarball-based images default to loopback, which Docker's published port cannot reach.
    withEnv("network.host", "0.0.0.0");
    withEnv(envVars);
    withCreateContainerCmdModifier(c -> c.withHostName(hostName));
    // Probe the published HTTPS port from the test JVM. A successful request inside the container
    // does not establish that the template script and Gravitino can reach OpenSearch.
    waitingFor(
        Wait.forHttps("/_cluster/health")
            .forPort(PORT)
            .allowInsecure()
            .withBasicCredentials(DEFAULT_USERNAME, DEFAULT_PASSWORD)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(2)));
  }

  public int getPort() {
    return getMappedPort(PORT);
  }

  public String getOpenSearchUrl() {
    return String.format("https://%s:%d", getHost(), getPort());
  }

  public static void main(String[] args) {
    OpenSearchContainer container =
        new OpenSearchContainer(
            "ci-opensearch",
            ImmutableMap.of(
                "discovery.type",
                "single-node",
                // "plugins.security.disabled", "true",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                DEFAULT_PASSWORD));
    container.start();

    LOG.info("OpenSearch started at: {}:{}", container.getHost(), container.getMappedPort(9200));
    long timestamp = System.currentTimeMillis();
    do {
      try {
        if (System.currentTimeMillis() - timestamp > Duration.ofSeconds(180).toMillis()) {
          LOG.info("OpenSearch will stop");
          break;
        }
        LOG.info("OpenSearch already started, mapped port {}", container.getMappedPort(PORT));
        Thread.sleep(Duration.ofSeconds(3).toMillis());
      } catch (InterruptedException e) {
        LOG.error("Error while waiting for OpenSearch to start", e);
      }
    } while (true);
  }
}

// Printing Container Log
final class PrintingContainerLog extends BaseConsumer<PrintingContainerLog> {
  public static final Logger LOG = LoggerFactory.getLogger(PrintingContainerLog.class);
  private final String prefix;

  public PrintingContainerLog(String prefix) {
    this.prefix = requireNonNull(prefix, "prefix is null");
  }

  @Override
  public void accept(OutputFrame outputFrame) {
    // remove new line characters
    String message = outputFrame.getUtf8String().replaceAll("\\r?\\n?$", "");
    if (!message.isEmpty() || outputFrame.getType() != END) {
      LOG.debug("{}{}", prefix, message);
    }
    if (outputFrame.getType() == END) {
      LOG.debug("{}(exited)", prefix);
    }
  }
}
