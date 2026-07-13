/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.auth.GroupMapper;
import org.apache.gravitino.auth.GroupMapperFactory;
import org.apache.gravitino.auth.PrincipalMapper;
import org.apache.gravitino.auth.PrincipalMapperFactory;

/** SCIM auxiliary service configuration loaded from {@code gravitino.datastrato.scim.*} keys. */
public final class ScimConfig {

  /** Default regex mapper type. */
  public static final String DEFAULT_MAPPER_TYPE = "regex";

  /** Default passthrough regex pattern. */
  public static final String DEFAULT_REGEX_PATTERN = "^(.*)$";

  private static final String USER_MAPPER_KEY = "scim.userMapper";
  private static final String USER_MAPPER_REGEX_PATTERN_KEY = "scim.userMapper.regex.pattern";
  private static final String GROUP_MAPPER_KEY = "scim.groupMapper";
  private static final String GROUP_MAPPER_REGEX_PATTERN_KEY = "scim.groupMapper.regex.pattern";

  private final PrincipalMapper userMapper;
  private final GroupMapper groupMapper;

  /**
   * Builds SCIM configuration from auxiliary service init properties.
   *
   * @param serviceInit short-key configuration map from {@code AuxiliaryServiceManager}
   * @param gravitinoConfig server configuration for mapper initialization
   */
  public ScimConfig(Map<String, String> serviceInit, Config gravitinoConfig) {
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
}
