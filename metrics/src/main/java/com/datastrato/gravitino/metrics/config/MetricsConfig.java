/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.config;

import java.util.Collections;
import java.util.List;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

public class MetricsConfig {
  private static final String PII_TAGS = "gravitino.datastrato.dashboardMetrics.piiTags";
  private static final String PUBLIC_TAGS = "gravitino.datastrato.dashboardMetrics.publicTags";
  private static final String CONFIDENTIAL_TAGS =
      "gravitino.datastrato.dashboardMetrics.confidentialTags";
  private static final String PRIVATE_TAGS = "gravitino.datastrato.dashboardMetrics.privateTags";
  private static final String RETENTION_DAYS =
      "gravitino.datastrato.dashboardMetrics.retentionDays";

  public static final ConfigEntry<List<String>> PII_TAGS_CONFIG =
      new ConfigBuilder(PII_TAGS)
          .doc("The tags that are considered PII (Personally Identifiable Information)")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .toSequence()
          .createWithDefault(Collections.emptyList());

  public static final ConfigEntry<List<String>> PUBLIC_TAGS_CONFIG =
      new ConfigBuilder(PUBLIC_TAGS)
          .doc("The tags that are considered Public")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .toSequence()
          .createWithDefault(Collections.emptyList());

  public static final ConfigEntry<List<String>> CONFIDENTIAL_TAGS_CONFIG =
      new ConfigBuilder(CONFIDENTIAL_TAGS)
          .doc("The tags that are considered Confidential")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .toSequence()
          .createWithDefault(Collections.emptyList());

  public static final ConfigEntry<List<String>> PRIVATE_TAGS_CONFIG =
      new ConfigBuilder(PRIVATE_TAGS)
          .doc("The tags that are considered Private")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .toSequence()
          .createWithDefault(Collections.emptyList());

  public static final ConfigEntry<Integer> RETENTION_DAYS_CONFIG =
      new ConfigBuilder(RETENTION_DAYS)
          .doc("The number of days to retain metrics data")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .checkValue(value -> value > 0, "Retention days must be greater than 0")
          .createWithDefault(30);
}
