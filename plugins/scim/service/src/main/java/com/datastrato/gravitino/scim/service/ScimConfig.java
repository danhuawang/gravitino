/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import java.util.Map;
import org.apache.gravitino.Config;
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

  private final PrincipalMapper userMapper;

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

    this.userMapper =
        PrincipalMapperFactory.create(userMapperType, userMapperPattern, gravitinoConfig);
  }

  /** Returns the SCIM user name mapper. */
  public PrincipalMapper userMapper() {
    return userMapper;
  }
}
