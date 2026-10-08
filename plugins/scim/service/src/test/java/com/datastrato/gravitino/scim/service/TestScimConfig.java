/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.junit.jupiter.api.Test;

class TestScimConfig {

  @Test
  void testMapperKeysFromAuxiliaryServiceInit() {
    ScimConfig config =
        new ScimConfig(
            Map.of(
                "userMapper",
                "regex",
                "userMapper.regex.pattern",
                "^(alice)$",
                "groupMapper",
                "regex",
                "groupMapper.regex.pattern",
                "^(engineers)$"),
            new Config() {});

    assertEquals("alice", ScimNameMappers.mapUserName(config.userMapper(), "alice"));
    assertEquals("engineers", ScimNameMappers.mapGroupName(config.groupMapper(), "engineers"));
  }

  @Test
  void testScimHttpAndHttpsPorts() {
    ScimConfig scimConfig = new ScimConfig(ImmutableMap.of(), new Config() {});
    JettyServerConfig jettyServerConfig = JettyServerConfig.fromConfig(scimConfig);
    assertEquals(ScimConfig.DEFAULT_HTTP_PORT, jettyServerConfig.getHttpPort());
    assertEquals(ScimConfig.DEFAULT_HTTPS_PORT, jettyServerConfig.getHttpsPort());
    assertFalse(jettyServerConfig.isEnableHttps());

    scimConfig =
        new ScimConfig(
            ImmutableMap.of(
                JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(),
                "9211",
                JettyServerConfig.WEBSERVER_HTTPS_PORT.getKey(),
                "9644"),
            new Config() {});
    jettyServerConfig = JettyServerConfig.fromConfig(scimConfig);
    assertEquals(9211, jettyServerConfig.getHttpPort());
    assertEquals(9644, jettyServerConfig.getHttpsPort());
  }
}
