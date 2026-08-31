/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino.lineage.sink;

import com.datastrato.gravitino.lineage.RunEventUtils;
import com.google.common.base.Preconditions;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClient;
import io.openlineage.client.transports.HttpConfig;
import io.openlineage.client.transports.TransportFactory;
import io.openlineage.server.OpenLineage.RunEvent;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.lineage.Utils;
import org.apache.gravitino.lineage.sink.LineageSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTPLineageSink implements LineageSink {

  private static final Logger LOG = LoggerFactory.getLogger(HTTPLineageSink.class);

  private OpenLineageClient client;

  @Override
  public void initialize(Map<String, String> properties) {
    HttpConfig httpConfig = new HttpConfig();
    HTTPLineageSinkConfig sinkConfig = new HTTPLineageSinkConfig(properties);
    try {
      String url = sinkConfig.get(HTTPLineageSinkConfig.URL);
      Preconditions.checkArgument(
          StringUtils.isNotBlank(url),
          HTTPLineageSinkConfig.URL_CONFIG_KEY + " is not set for http lineage sink");
      httpConfig.setUrl(new URI(url));
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    int timeout = sinkConfig.get(HTTPLineageSinkConfig.TIMEOUT);
    httpConfig.setTimeoutInMillis(timeout);
    this.client =
        OpenLineageClient.builder().transport(new TransportFactory(httpConfig).build()).build();
  }

  @Override
  public void close() {
    try {
      if (client != null) {
        client.close();
      }
    } catch (Exception e) {
      LOG.warn("Close OpenLineage client failed,", e);
    }
  }

  @Override
  public void sink(RunEvent event) {
    try {
      OpenLineage.RunEvent clientEvent = RunEventUtils.getClientRunEvent(event);
      client.emit(clientEvent);
    } catch (Exception e) {
      LOG.warn(
          "Sink run event failed, run id: {}, job name: {}, error message: {}",
          Utils.getRunID(event),
          Utils.getJobName(event),
          e.getMessage());
    }
  }
}
