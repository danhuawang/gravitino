/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.internal;

import com.datastrato.gravitino.server.web.InternalApiPaths;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

/** Configuration entries for Enterprise internal REST APIs. */
public final class InternalApiConfigs {

  /** Whether requests to internal REST APIs must carry the internal origin header. */
  public static final ConfigEntry<Boolean> REQUIRE_ORIGIN_HEADER =
      new ConfigBuilder("gravitino.datastrato.internalApi.requireOriginHeader")
          .doc(
              "Whether requests to REST APIs under /api/internal must carry the "
                  + InternalApiPaths.ORIGIN_HEADER
                  + " header. Internal APIs are used by the Web UI and bundled tools and are not"
                  + " part of the public REST API.")
          .version(ConfigConstants.VERSION_1_3_0)
          .booleanConf()
          .createWithDefault(true);

  private InternalApiConfigs() {}
}
