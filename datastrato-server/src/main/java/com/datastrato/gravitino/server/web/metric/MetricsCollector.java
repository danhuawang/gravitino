/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

// We have placed this class in the server module rather than the core module because later
// authentication relies on
// org.apache.gravitino.server.authorization.MetadataFilterHelper#filterByPrivilege in the
// server-common.
package com.datastrato.gravitino.server.web.metric;

import com.datastrato.gravitino.metrics.MetricDataService;
import java.io.Closeable;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.server.ServerConfig;
import org.apache.gravitino.tag.TagDispatcher;

public class MetricsCollector implements Closeable {
  private final MetalakeDispatcher metalakeDispatcher;
  private final CatalogDispatcher catalogDispatcher;
  private final SchemaDispatcher schemaDispatcher;
  private final TableDispatcher tableDispatcher;
  private final FilesetDispatcher filesetDispatcher;
  private final TopicDispatcher topicDispatcher;
  private final ModelDispatcher modelDispatcher;
  private final TagDispatcher tagDispatcher;
  private final AccessControlDispatcher accessControlDispatcher;
  private final MetricDataService metricDataService;

  public MetricsCollector(
      ServerConfig serverConfig,
      MetalakeDispatcher dispatcher,
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      TagDispatcher tagDispatcher,
      AccessControlDispatcher accessControlDispatcher,
      MetricDataService metricDataService) {
    this.metalakeDispatcher = dispatcher;
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = schemaDispatcher;
    this.tableDispatcher = tableDispatcher;
    this.filesetDispatcher = filesetDispatcher;
    this.topicDispatcher = topicDispatcher;
    this.modelDispatcher = modelDispatcher;
    this.tagDispatcher = tagDispatcher;
    this.accessControlDispatcher = accessControlDispatcher;
    this.metricDataService = metricDataService;
  }

  public void start() {
    // todo: implement this method to start the metrics collector
  }

  @Override
  public void close() {
    // todo: implement this method to close the metrics collector
  }

  public void refreshMetricsForUser(String metalakeName, String userName) {
    // todo: implement this method to refresh metrics for a specific user
  }
}
