/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.storage.relational.utils;

import com.datastrato.gravitino.metrics.dto.MetricDTO;
import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class DatastratoPOConverters {
  private DatastratoPOConverters() {}

  public static MetricDTO[] fromMetricPOs(List<MetricPO> metricPOs) {
    if (metricPOs == null || metricPOs.isEmpty()) {
      return new MetricDTO[0];
    }

    return metricPOs.stream()
        .collect(Collectors.groupingBy(MetricPO::getMetricName, TreeMap::new, Collectors.toList()))
        .entrySet()
        .stream()
        .map(
            entry -> {
              String name = entry.getKey();
              List<MetricPO> pos = entry.getValue();

              Double[] values = pos.stream().map(MetricPO::getMetricValue).toArray(Double[]::new);
              long[] timestamps =
                  pos.stream().mapToLong(po -> po.getCreatedTime().getTime()).toArray();
              MetricState[] states =
                  pos.stream().map(MetricPO::getMetricState).toArray(MetricState[]::new);
              String[] messages =
                  pos.stream().map(MetricPO::getMetricMessage).toArray(String[]::new);

              return MetricDTO.builder()
                  .withName(name)
                  .withValues(values)
                  .withTimestamps(timestamps)
                  .withStates(states)
                  .withMessages(messages)
                  .build();
            })
        .toArray(MetricDTO[]::new);
  }
}
