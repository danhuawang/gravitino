/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;
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

  static final ConfigEntry<Integer> CHECK_INTERVAL_HOURS =
      new ConfigBuilder("gravitino.datastrato.license.checkIntervalHours")
          .doc("How often the periodic expiry check runs (hours)")
          .version("1.3.0")
          .intConf()
          .checkValue(h -> h > 0, "Check interval must be positive")
          .createWithDefault(24);

  static final ConfigEntry<Integer> NODE_HEARTBEAT_INTERVAL_MINUTES =
      new ConfigBuilder("gravitino.datastrato.license.nodeHeartbeatIntervalMinutes")
          .doc("How often the node heartbeat and maxNodes enforcement check run (minutes)")
          .version("1.3.0")
          .intConf()
          .checkValue(m -> m > 0, "Node heartbeat interval must be positive")
          .createWithDefault(5);

  static final ConfigEntry<Integer> NODE_STALE_MINUTES =
      new ConfigBuilder("gravitino.datastrato.license.nodeStaleMinutes")
          .doc("Minutes without heartbeat before a node row is pruned")
          .version("1.3.0")
          .intConf()
          .checkValue(m -> m > 0, "Node stale minutes must be positive")
          .createWithDefault(15);

  static final ConfigEntry<Integer> WARN_DAYS_BEFORE_EXPIRY =
      new ConfigBuilder("gravitino.datastrato.license.warnDaysBeforeExpiry")
          .doc("Start daily warnings this many days before expiresAt")
          .version("1.3.0")
          .intConf()
          .checkValue(d -> d >= 0, "Warn days before expiry must be non-negative")
          .createWithDefault(30);

  static final ConfigEntry<String> RENEWAL_CONTACT_URL =
      new ConfigBuilder("gravitino.datastrato.license.renewalContactUrl")
          .doc("URL shown in banner and warning logs when renewal is needed")
          .version("1.3.0")
          .stringConf()
          .checkValue(StringUtils::isNotBlank, "Renewal contact URL must not be blank")
          .createWithDefault("https://datastrato.ai/renew");

  public LicenseConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }

  public int getCheckIntervalHours() {
    return get(CHECK_INTERVAL_HOURS);
  }

  public int getNodeHeartbeatIntervalMinutes() {
    return get(NODE_HEARTBEAT_INTERVAL_MINUTES);
  }

  public int getNodeStaleMinutes() {
    return get(NODE_STALE_MINUTES);
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
}
