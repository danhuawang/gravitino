/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.utils;

import com.datastrato.gravitino.metrics.dto.MetricDTO;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import java.util.List;
import java.util.stream.Collectors;

public class DatastratoPOConverters {
  private DatastratoPOConverters() {}

  public static MetricDTO[] fromMetricPOs(List<MetricPO> metricPOs) {
    if (metricPOs == null || metricPOs.isEmpty()) {
      return new MetricDTO[0];
    }

    return metricPOs.stream()
        .collect(Collectors.groupingBy(MetricPO::getMetricName))
        .entrySet()
        .stream()
        .map(
            entry -> {
              String name = entry.getKey();
              List<MetricPO> pos = entry.getValue();

              double[] values = pos.stream().mapToDouble(MetricPO::getMetricValue).toArray();
              long[] timestamps =
                  pos.stream().mapToLong(po -> po.getCreatedTime().getTime()).toArray();

              return MetricDTO.builder()
                  .withName(name)
                  .withValues(values)
                  .withTimestamps(timestamps)
                  .build();
            })
        .toArray(MetricDTO[]::new);
  }
}
