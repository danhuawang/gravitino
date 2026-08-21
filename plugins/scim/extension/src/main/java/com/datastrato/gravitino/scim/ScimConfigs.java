/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim;

import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

/** Configuration entries for the SCIM plugin. */
public final class ScimConfigs {

  /** Configuration key for SCIM error history retention days. */
  public static final String ERROR_HISTORY_RETENTION_DAYS_KEY =
      "gravitino.scim.errorHistory.retentionDays";

  /** Default number of days to retain SCIM error history rows. */
  public static final int DEFAULT_ERROR_HISTORY_RETENTION_DAYS = 30;

  /**
   * Days to retain SCIM protocol error history. A dedicated cleaner deletes rows older than this
   * once per day.
   */
  public static final ConfigEntry<Integer> ERROR_HISTORY_RETENTION_DAYS =
      new ConfigBuilder(ERROR_HISTORY_RETENTION_DAYS_KEY)
          .doc(
              "Days to retain SCIM protocol error history rows. A dedicated cleaner deletes rows"
                  + " older than this once per day.")
          .version(ConfigConstants.VERSION_1_3_0)
          .intConf()
          .checkValue(value -> value > 0, ConfigConstants.POSITIVE_NUMBER_ERROR_MSG)
          .createWithDefault(DEFAULT_ERROR_HISTORY_RETENTION_DAYS);

  private ScimConfigs() {}
}
