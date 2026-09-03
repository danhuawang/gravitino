/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.relational.converters;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestScimSQLExceptionConverterFactory {

  @AfterEach
  void tearDown() {
    ScimSQLExceptionConverterFactory.close();
  }

  @Test
  void testInitConverterAcceptsDefaultH2Url() {
    Config config = configWithUrl(Configs.DEFAULT_RELATIONAL_JDBC_BACKEND_URL);

    assertDoesNotThrow(() -> ScimSQLExceptionConverterFactory.initConverter(config));
    assertNotNull(ScimSQLExceptionConverterFactory.getConverter());
  }

  @Test
  void testInitConverterAcceptsH2FileUrl() {
    Config config = configWithUrl("jdbc:h2:file:/tmp/gravitino;MODE=MYSQL");

    assertDoesNotThrow(() -> ScimSQLExceptionConverterFactory.initConverter(config));
    assertNotNull(ScimSQLExceptionConverterFactory.getConverter());
  }

  @Test
  void testInitConverterAcceptsMysqlUrl() {
    Config config = configWithUrl("jdbc:mysql://127.0.0.1:3306/gravitino");

    assertDoesNotThrow(() -> ScimSQLExceptionConverterFactory.initConverter(config));
    assertNotNull(ScimSQLExceptionConverterFactory.getConverter());
  }

  private static Config configWithUrl(String jdbcUrl) {
    Map<String, String> properties = new HashMap<>();
    properties.put(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL_KEY, jdbcUrl);
    Config config = new Config(false) {};
    config.loadFromMap(properties, k -> true);
    return config;
  }
}
