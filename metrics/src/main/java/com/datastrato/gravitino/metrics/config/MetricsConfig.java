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
  private static final String INCREMENTAL_POLL_INTERVAL_MS =
      "gravitino.metrics.incremental.pollIntervalMs";
  private static final String INCREMENTAL_DEBOUNCE_MS = "gravitino.metrics.incremental.debounceMs";
  private static final String INCREMENTAL_MAX_DEBOUNCE_MS =
      "gravitino.metrics.incremental.maxDebounceMs";
  private static final String INCREMENTAL_RETRY_INITIAL_MS =
      "gravitino.metrics.incremental.retryInitialMs";
  private static final String INCREMENTAL_RETRY_MAX_MS = "gravitino.metrics.incremental.retryMaxMs";

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

  /** Interval between polls for metalakes pending a metrics refresh. */
  public static final ConfigEntry<Long> INCREMENTAL_POLL_INTERVAL_MS_CONFIG =
      positiveMillisecondsConfig(
          INCREMENTAL_POLL_INTERVAL_MS,
          "Interval for polling metalakes pending dashboard metric refresh",
          1_000L);

  /** Quiet period used to coalesce adjacent metadata events. */
  public static final ConfigEntry<Long> INCREMENTAL_DEBOUNCE_MS_CONFIG =
      positiveMillisecondsConfig(
          INCREMENTAL_DEBOUNCE_MS, "Quiet time before recomputing dashboard metrics", 1_000L);

  /** Maximum time that an event burst may be coalesced. */
  public static final ConfigEntry<Long> INCREMENTAL_MAX_DEBOUNCE_MS_CONFIG =
      positiveMillisecondsConfig(
          INCREMENTAL_MAX_DEBOUNCE_MS, "Maximum time to coalesce dashboard metric events", 5_000L);

  /** Initial delay after an incremental recomputation failure. */
  public static final ConfigEntry<Long> INCREMENTAL_RETRY_INITIAL_MS_CONFIG =
      positiveMillisecondsConfig(
          INCREMENTAL_RETRY_INITIAL_MS,
          "Initial delay after a dashboard metric refresh failure",
          1_000L);

  /** Maximum delay between incremental recomputation retries. */
  public static final ConfigEntry<Long> INCREMENTAL_RETRY_MAX_MS_CONFIG =
      positiveMillisecondsConfig(
          INCREMENTAL_RETRY_MAX_MS,
          "Maximum delay between dashboard metric refresh retries",
          60_000L);

  private static ConfigEntry<Long> positiveMillisecondsConfig(
      String key, String description, long defaultValue) {
    return new ConfigBuilder(key)
        .doc(description + " in milliseconds")
        .version(ConfigConstants.VERSION_1_3_0)
        .longConf()
        .checkValue(value -> value > 0, "Value must be greater than 0")
        .createWithDefault(defaultValue);
  }
}
