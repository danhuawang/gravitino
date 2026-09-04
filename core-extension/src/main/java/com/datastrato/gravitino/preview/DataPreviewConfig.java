/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.preview;

import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

public class DataPreviewConfig {
  /** Whether data preview is enabled. */
  public static final ConfigEntry<Boolean> ENABLED_CONFIG =
      new ConfigBuilder("gravitino.datastrato.preview.enabled")
          .doc("Whether to enable data preview")
          .version(ConfigConstants.VERSION_0_9_0)
          .booleanConf()
          .createWithDefault(true);

  public static String JDBC_URL = "gravitino.datastrato.preview.jdbcUrl";
  public static String JDBC_DRIVER = "gravitino.datastrato.preview.jdbcDriver";
  public static String JDBC_USERNAME = "gravitino.datastrato.preview.jdbcUsername";
  public static String JDBC_PASSWORD = "gravitino.datastrato.preview.jdbcPassword";
  public static String TIMEOUT = "gravitino.datastrato.preview.timeoutInSec";
  public static String MAX_ROW_COUNT = "gravitino.datastrato.preview.maxRowCount";

  public static String SENSITIVE_TAGS = "gravitino.datastrato.preview.sensitiveTags";

  public static final ConfigEntry<String> JDBC_URL_CONFIG =
      new ConfigBuilder(JDBC_URL)
          .doc("The jdbc url for Trino preview service")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<String> JDBC_DRIVER_CONFIG =
      new ConfigBuilder(JDBC_DRIVER)
          .doc("The jdbc driver for Trino preview service")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .createWithDefault("io.trino.jdbc.TrinoDriver");

  public static final ConfigEntry<String> JDBC_USERNAME_CONFIG =
      new ConfigBuilder(JDBC_USERNAME)
          .doc("The jdbc username for Trino preview service")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<String> JDBC_PASSWORD_CONFIG =
      new ConfigBuilder(JDBC_PASSWORD)
          .doc("The jdbc password for Trino preview service")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .create();

  public static final ConfigEntry<Integer> TIMEOUT_CONFIG =
      new ConfigBuilder(TIMEOUT)
          .doc("The fetch result timeout for Trino preview service")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(300);

  public static final ConfigEntry<Integer> MAX_ROW_COUNT_CONFIG =
      new ConfigBuilder(MAX_ROW_COUNT)
          .doc("The max fetch result row count for Trino preview service")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(100);

  public static final ConfigEntry<List<String>> SENSITIVE_TAGS_CONFIG =
      new ConfigBuilder(SENSITIVE_TAGS)
          .doc("The tags which associated with sensitive data")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .toSequence()
          .createWithDefault(Collections.emptyList());
}
