/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigEntry;

public class LicenseConfig extends Config {

  public static final String LICENSE_KEY_ENV = "GRAVITINO_LICENSE_KEY";

  static final ConfigEntry<String> LICENSE_KEY =
      new ConfigBuilder("gravitino.datastrato.license.key")
          .doc("The GRAV-... license key string")
          .version("1.3.0")
          .stringConf()
          .create();

  static final ConfigEntry<String> NODE_ID =
      new ConfigBuilder("gravitino.datastrato.license.nodeId")
          .doc("Unique identifier for this server node (required; must be stable across restarts)")
          .version("1.3.0")
          .stringConf()
          .create();

  static final ConfigEntry<Integer> CHECK_INTERVAL_HOURS =
      new ConfigBuilder("gravitino.datastrato.license.checkIntervalHours")
          .doc("How often the periodic expiry check and node heartbeat run (hours)")
          .version("1.3.0")
          .intConf()
          .createWithDefault(24);

  static final ConfigEntry<Integer> NODE_STALE_HOURS =
      new ConfigBuilder("gravitino.datastrato.license.nodeStaleHours")
          .doc("Hours after which a node with no heartbeat is considered stale")
          .version("1.3.0")
          .intConf()
          .createWithDefault(48);

  static final ConfigEntry<Integer> WARN_DAYS_BEFORE_EXPIRY =
      new ConfigBuilder("gravitino.datastrato.license.warnDaysBeforeExpiry")
          .doc("Start daily warnings this many days before expiresAt")
          .version("1.3.0")
          .intConf()
          .createWithDefault(30);

  static final ConfigEntry<String> RENEWAL_CONTACT_URL =
      new ConfigBuilder("gravitino.datastrato.license.renewalContactUrl")
          .doc("URL shown in banner and warning logs when renewal is needed")
          .version("1.3.0")
          .stringConf()
          .createWithDefault("https://datastrato.ai/renew");

  public LicenseConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }

  public int getCheckIntervalHours() {
    return get(CHECK_INTERVAL_HOURS);
  }

  public int getNodeStaleHours() {
    return get(NODE_STALE_HOURS);
  }

  public int getWarnDaysBeforeExpiry() {
    return get(WARN_DAYS_BEFORE_EXPIRY);
  }

  public String getRenewalContactUrl() {
    return get(RENEWAL_CONTACT_URL);
  }

  public String getLicenseKey() {
    return get(LICENSE_KEY);
  }

  public String getNodeId() {
    return get(NODE_ID);
  }
}
