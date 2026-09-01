/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.web.rest.feature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.scim.v2.SystemExitTestHelper;
import com.datastrato.gravitino.scim.v2.SystemExitTestHelper.SystemExitException;
import com.datastrato.gravitino.scim.v2.basic.oauth.ScimOAuthPrincipalMapper;
import com.datastrato.gravitino.scim.v2.basic.oauth.ScimOAuthRequestPathFilter;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.junit.jupiter.api.Test;

class TestScimTokenRESTFeature {

  private static Config config(Map<String, String> values) {
    Config config = new Config(false) {};
    config.loadFromMap(values, key -> true);
    return config;
  }

  private static Map<String, String> validScimConfig() {
    return ImmutableMap.of(
        AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
            + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
        "scim-v2",
        OAuthConfig.PRINCIPAL_MAPPER.getKey(),
        ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME,
        OAuthConfig.GROUPS_FIELDS.getKey(),
        "",
        JettyServerConfig.GRAVITINO_SERVER_CONFIG_PREFIX
            + JettyServerConfig.CUSTOM_FILTERS.getKey(),
        ScimOAuthRequestPathFilter.FILTER_CLASS_NAME,
        Configs.ENABLE_AUTHORIZATION.getKey(),
        "true");
  }

  @Test
  void testNoAux() {
    Config config = config(ImmutableMap.of());

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> ScimTokenRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testNoScim() {
    Config config =
        config(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "iceberg-rest"));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> ScimTokenRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testNoMapper() {
    Config config =
        config(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "scim"));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> ScimTokenRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testRejectGroupsFields() {
    Config config =
        config(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "scim-v2",
                OAuthConfig.PRINCIPAL_MAPPER.getKey(),
                ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME,
                OAuthConfig.GROUPS_FIELDS.getKey(),
                "roles",
                JettyServerConfig.GRAVITINO_SERVER_CONFIG_PREFIX
                    + JettyServerConfig.CUSTOM_FILTERS.getKey(),
                ScimOAuthRequestPathFilter.FILTER_CLASS_NAME));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> ScimTokenRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testRejectNoContextFilter() {
    Config config =
        config(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "scim-v2",
                OAuthConfig.PRINCIPAL_MAPPER.getKey(),
                ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME,
                OAuthConfig.GROUPS_FIELDS.getKey(),
                ""));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> ScimTokenRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testRejectAuthorizationDisabled() {
    Config config =
        config(
            ImmutableMap.of(
                AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
                    + AuxiliaryServiceManager.AUX_SERVICE_NAMES,
                "scim-v2",
                OAuthConfig.PRINCIPAL_MAPPER.getKey(),
                ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME,
                OAuthConfig.GROUPS_FIELDS.getKey(),
                "",
                JettyServerConfig.GRAVITINO_SERVER_CONFIG_PREFIX
                    + JettyServerConfig.CUSTOM_FILTERS.getKey(),
                ScimOAuthRequestPathFilter.FILTER_CLASS_NAME,
                Configs.ENABLE_AUTHORIZATION.getKey(),
                "false"));

    SystemExitException exception =
        assertThrows(
            SystemExitException.class,
            () ->
                SystemExitTestHelper.runWithExitGuard(
                    () -> ScimTokenRESTFeature.validateConfiguration(config)));

    assertEquals(1, exception.status());
  }

  @Test
  void testOk() {
    Config config = config(validScimConfig());

    assertDoesNotThrow(() -> ScimTokenRESTFeature.validateConfiguration(config));
  }
}
