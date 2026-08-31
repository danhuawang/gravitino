/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.OverwriteDefaultConfig;
import org.apache.gravitino.auth.GroupMapper;
import org.apache.gravitino.auth.GroupMapperFactory;
import org.apache.gravitino.auth.PrincipalMapper;
import org.apache.gravitino.auth.PrincipalMapperFactory;
import org.apache.gravitino.config.ConfigConstants;

/**
 * SCIM auxiliary service configuration.
 *
 * <p>{@link org.apache.gravitino.auxiliary.AuxiliaryServiceManager} forwards {@code
 * gravitino.scim.*} entries with the {@code scim.} prefix stripped (e.g. {@code userMapper}, {@code
 * httpPort}).
 */
public final class ScimConfig extends Config implements OverwriteDefaultConfig {

  /** Default SCIM auxiliary HTTP port. */
  public static final int DEFAULT_HTTP_PORT = 9201;

  /** Default regex mapper type. */
  public static final String DEFAULT_MAPPER_TYPE = "regex";

  /** Default passthrough regex pattern. */
  public static final String DEFAULT_REGEX_PATTERN = "^(.*)$";

  private static final String USER_MAPPER_KEY = "userMapper";
  private static final String USER_MAPPER_REGEX_PATTERN_KEY = "userMapper.regex.pattern";
  private static final String GROUP_MAPPER_KEY = "groupMapper";
  private static final String GROUP_MAPPER_REGEX_PATTERN_KEY = "groupMapper.regex.pattern";

  private final PrincipalMapper userMapper;
  private final GroupMapper groupMapper;

  /**
   * Builds SCIM configuration from auxiliary service init properties.
   *
   * @param serviceInit short-key configuration map from {@code AuxiliaryServiceManager}
   * @param gravitinoConfig server configuration for mapper initialization
   */
  public ScimConfig(Map<String, String> serviceInit, Config gravitinoConfig) {
    super(false);
    loadFromMap(serviceInit, key -> true);

    String userMapperType = serviceInit.getOrDefault(USER_MAPPER_KEY, DEFAULT_MAPPER_TYPE);
    String userMapperPattern =
        serviceInit.getOrDefault(USER_MAPPER_REGEX_PATTERN_KEY, DEFAULT_REGEX_PATTERN);
    String groupMapperType = serviceInit.getOrDefault(GROUP_MAPPER_KEY, DEFAULT_MAPPER_TYPE);
    String groupMapperPattern =
        serviceInit.getOrDefault(GROUP_MAPPER_REGEX_PATTERN_KEY, DEFAULT_REGEX_PATTERN);

    this.userMapper =
        PrincipalMapperFactory.create(userMapperType, userMapperPattern, gravitinoConfig);
    this.groupMapper =
        GroupMapperFactory.create(groupMapperType, groupMapperPattern, gravitinoConfig);
  }

  /** Returns the SCIM user name mapper. */
  public PrincipalMapper userMapper() {
    return userMapper;
  }

  /** Returns the SCIM group display name mapper. */
  public GroupMapper groupMapper() {
    return groupMapper;
  }

  @Override
  public Map<String, String> getOverwriteDefaultConfig() {
    return ImmutableMap.of(ConfigConstants.WEBSERVER_HTTP_PORT, String.valueOf(DEFAULT_HTTP_PORT));
  }
}
