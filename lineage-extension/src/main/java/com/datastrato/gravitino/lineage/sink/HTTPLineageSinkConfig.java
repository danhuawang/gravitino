/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino.lineage.sink;

import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

public class HTTPLineageSinkConfig extends Config {

  static final String URL_CONFIG_KEY = "url";

  public static final ConfigEntry<String> URL =
      new ConfigBuilder(URL_CONFIG_KEY)
          .doc("The url of the openLineage service")
          .version(ConfigConstants.VERSION_0_9_0)
          .stringConf()
          .create();

  public static final ConfigEntry<Integer> TIMEOUT =
      new ConfigBuilder("timeoutInMs")
          .doc("The timeout of connect to lineage service")
          .version(ConfigConstants.VERSION_0_9_0)
          .intConf()
          .createWithDefault(Integer.valueOf(10000));

  public HTTPLineageSinkConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }
}
