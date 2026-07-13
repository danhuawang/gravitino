/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import java.util.Map;
import org.apache.gravitino.Config;
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
}
