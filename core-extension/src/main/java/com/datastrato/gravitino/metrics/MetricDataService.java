/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import com.datastrato.gravitino.dto.metrics.MetricDTO;
import org.apache.gravitino.Config;

public class MetricDataService {

  public MetricDataService(Config config) {
    // todo: implement this constructor to initialize the data source
  }

  public MetricDTO[] getMetricsByNameAndTimestamp(
      String metalakeName,
      String userName,
      String[] metricNames,
      long startTimestamp,
      long endTimestamp) {
    // todo: implement this method
    return new MetricDTO[0];
  }
}
