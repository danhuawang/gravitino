/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.integration.test;

import com.datastrato.gravitino.scim.v2.basic.oauth.ScimOAuthPrincipalMapper;
import com.datastrato.gravitino.scim.v2.basic.oauth.ScimOAuthRequestPathFilter;
import com.google.common.collect.ForwardingMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.apache.gravitino.server.web.JettyServerConfig;

/** Server configuration helpers for {@link ScimTokenRESTApiIT}. */
final class ScimTokenITServerSupport {

  private static final String SCIM_CONFIG_PREFIX = "gravitino.scim.";
  private static final String AUX_SERVICE_NAMES_KEY =
      AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX
          + AuxiliaryServiceManager.AUX_SERVICE_NAMES;

  private ScimTokenITServerSupport() {}

  /**
   * Returns MiniGravitino overrides required by {@link
   * com.datastrato.gravitino.scim.v2.web.rest.feature.ScimTokenRESTFeature}.
   */
  static Map<String, String> tokenAdminServerConfigs() throws IOException {
    Map<String, String> configs = new HashMap<>();
    configs.put(AUX_SERVICE_NAMES_KEY, "scim");
    configs.put(
        OAuthConfig.PRINCIPAL_MAPPER.getKey(),
        ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME);
    configs.put(OAuthConfig.GROUPS_FIELDS.getKey(), "");
    configs.put(
        JettyServerConfig.GRAVITINO_SERVER_CONFIG_PREFIX
            + JettyServerConfig.CUSTOM_FILTERS.getKey(),
        ScimOAuthRequestPathFilter.FILTER_CLASS_NAME);
    configs.put(
        SCIM_CONFIG_PREFIX + AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH,
        scimPlaceholderAuxClasspath());
    configs.put(
        SCIM_CONFIG_PREFIX + JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(),
        String.valueOf(RESTUtils.findAvailablePort(9200, 10000)));
    return configs;
  }

  private static String scimPlaceholderAuxClasspath() {
    String projectBuildDir = System.getenv("IT_PROJECT_DIR");
    if (StringUtils.isNotBlank(projectBuildDir)) {
      Path buildDir = Path.of(projectBuildDir);
      return buildDir.resolve("classes/java/test") + "," + buildDir.resolve("resources/test");
    }

    String rootDir = System.getenv("GRAVITINO_ROOT_DIR");
    if (StringUtils.isBlank(rootDir)) {
      rootDir = System.getenv("GRAVITINO_HOME");
    }
    if (StringUtils.isBlank(rootDir)) {
      throw new IllegalStateException(
          "IT_PROJECT_DIR or GRAVITINO_ROOT_DIR must be set for SCIM token IT");
    }
    Path extensionBuildDir = Path.of(rootDir, "plugins/scim/extension/build");
    return extensionBuildDir.resolve("classes/java/test")
        + ","
        + extensionBuildDir.resolve("resources/test");
  }

  /**
   * Wraps {@code delegate} so {@link org.apache.gravitino.integration.test.util.BaseIT} cannot
   * overwrite {@code gravitino.auxService.names} with iceberg/lance defaults.
   */
  static Map<String, String> preserveScimAuxServiceNames(Map<String, String> delegate) {
    return new ScimAuxServiceNamesConfigMap(delegate);
  }

  private static final class ScimAuxServiceNamesConfigMap extends ForwardingMap<String, String> {

    private final Map<String, String> delegate;

    private ScimAuxServiceNamesConfigMap(Map<String, String> delegate) {
      this.delegate = delegate;
    }

    @Override
    protected Map<String, String> delegate() {
      return delegate;
    }

    @Override
    public String put(String key, String value) {
      if (AUX_SERVICE_NAMES_KEY.equals(key)) {
        return delegate.put(key, "scim");
      }
      return delegate.put(key, value);
    }
  }
}
