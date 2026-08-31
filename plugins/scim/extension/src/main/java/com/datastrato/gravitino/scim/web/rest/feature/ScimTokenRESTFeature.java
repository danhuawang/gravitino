/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest.feature;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper;
import com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter;
import com.datastrato.gravitino.scim.web.rest.ScimProvisioningOperations;
import com.datastrato.gravitino.scim.web.rest.ScimTokenBinder;
import com.datastrato.gravitino.scim.web.rest.ScimTokenOperations;
import com.google.common.base.Splitter;
import java.util.List;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers SCIM token admin REST resources on the main Gravitino server.
 *
 * <p>Configure {@code gravitino.server.rest.extensionPackages} to include {@link
 * #SCIM_TOKEN_REST_EXTENSION_PACKAGE} and list {@code scim} in {@code gravitino.auxService.names}
 * so Jersey auto-discovers this feature.
 */
@Provider
public class ScimTokenRESTFeature implements Feature {

  private static final Logger LOG = LoggerFactory.getLogger(ScimTokenRESTFeature.class);

  private static final Splitter AUX_SERVICE_NAME_SPLITTER =
      Splitter.on(',').omitEmptyStrings().trimResults();

  private static final String SCIM_AUX_SERVICE_NAME = "scim";

  /** Extension package name registered through {@code gravitino.server.rest.extensionPackages}. */
  public static final String SCIM_TOKEN_REST_EXTENSION_PACKAGE =
      ScimTokenRESTFeature.class.getPackageName();

  @Override
  public boolean configure(FeatureContext context) {
    GravitinoEnv env = GravitinoEnv.getInstance();
    Config config = env.config();
    validateConfiguration(config);

    ScimTokenManager.getInstance().initialize(config, env.entityStore(), env.idGenerator());
    ScimUserGroupRelManager.getInstance().initialize(config);

    context.register(ScimTokenBinder.class);
    context.register(ScimTokenOperations.class);
    context.register(ScimProvisioningOperations.class);
    LOG.info(
        "SCIM OAuth authorization uses user_group_rel via {}",
        ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME);
    LOG.info("Initialized SCIM token manager for token admin REST APIs");
    return true;
  }

  /**
   * Validates that the server configuration is compatible with the SCIM token admin plugin.
   *
   * <p>Called when the SCIM token REST extension package is enabled. Requires {@code scim} in
   * {@code gravitino.auxService.names}.
   *
   * <p>Requires {@code gravitino.authenticator.oauth.principalMapper} to be {@link
   * ScimOAuthPrincipalMapper#PRINCIPAL_MAPPER_CLASS_NAME}, {@code groupsFields} to be empty, {@code
   * gravitino.server.webserver.customFilters} to include {@link
   * ScimOAuthRequestPathFilter#FILTER_CLASS_NAME}, and {@code gravitino.authorization.enable} to be
   * {@code true}.
   *
   * @param config the server configuration
   */
  static void validateConfiguration(Config config) {
    if (!isScimAuxServiceEnabled(config)) {
      LOG.error(
          "gravitino.server.rest.extensionPackages includes the SCIM token admin plugin ({}) but "
              + "'scim' is not listed in gravitino.auxService.names. Add 'scim' to "
              + "gravitino.auxService.names.",
          SCIM_TOKEN_REST_EXTENSION_PACKAGE);
      System.exit(1);
    }
    if (!isScimPrincipalMapperConfigured(config)) {
      LOG.error(
          "gravitino.server.rest.extensionPackages includes the SCIM token admin plugin ({}) but "
              + "gravitino.authenticator.oauth.principalMapper is not set to {}. Configure "
              + "gravitino.authenticator.oauth.principalMapper to use SCIM membership groups.",
          SCIM_TOKEN_REST_EXTENSION_PACKAGE,
          ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME);
      System.exit(1);
    }
    if (!usesEmptyGroupsFields(config)) {
      LOG.error(
          "gravitino.server.rest.extensionPackages includes the SCIM token admin plugin ({}) but "
              + "gravitino.authenticator.oauth.groupsFields must be empty when "
              + "gravitino.authenticator.oauth.principalMapper is set to {}. Clear groupsFields "
              + "so OAuth validation does not overwrite SCIM membership groups.",
          SCIM_TOKEN_REST_EXTENSION_PACKAGE,
          ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME);
      System.exit(1);
    }
    if (!isScimContextFilterConfigured(config)) {
      LOG.error(
          "gravitino.server.rest.extensionPackages includes the SCIM token admin plugin ({}) but "
              + "gravitino.server.webserver.customFilters does not include {}. Add {} to "
              + "gravitino.server.webserver.customFilters so SCIM OAuth can resolve "
              + "metalake-scoped membership groups from the request path.",
          SCIM_TOKEN_REST_EXTENSION_PACKAGE,
          ScimOAuthRequestPathFilter.FILTER_CLASS_NAME,
          ScimOAuthRequestPathFilter.FILTER_CLASS_NAME);
      System.exit(1);
    }
    if (!isAuthorizationEnabled(config)) {
      LOG.error(
          "gravitino.server.rest.extensionPackages includes the SCIM token admin plugin ({}) but "
              + "gravitino.authorization.enable is not true. Set gravitino.authorization.enable=true "
              + "so SCIM token admin APIs can enforce metalake ownership checks.",
          SCIM_TOKEN_REST_EXTENSION_PACKAGE);
      System.exit(1);
    }
  }

  private static boolean isAuthorizationEnabled(Config config) {
    return Boolean.TRUE.equals(config.get(Configs.ENABLE_AUTHORIZATION));
  }

  private static boolean isScimContextFilterConfigured(Config config) {
    return JettyServerConfig.fromConfig(config, JettyServerConfig.GRAVITINO_SERVER_CONFIG_PREFIX)
        .getCustomFilters()
        .contains(ScimOAuthRequestPathFilter.FILTER_CLASS_NAME);
  }

  private static boolean usesEmptyGroupsFields(Config config) {
    List<String> groupsFields = config.get(OAuthConfig.GROUPS_FIELDS);
    return groupsFields == null || groupsFields.isEmpty();
  }

  private static boolean isScimPrincipalMapperConfigured(Config config) {
    String principalMapper = config.get(OAuthConfig.PRINCIPAL_MAPPER);
    return ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME.equals(principalMapper);
  }

  private static boolean isScimAuxServiceEnabled(Config config) {
    String auxServiceNames =
        config
            .getConfigsWithPrefix(AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX)
            .getOrDefault(AuxiliaryServiceManager.AUX_SERVICE_NAMES, "");
    if (StringUtils.isBlank(auxServiceNames)) {
      return false;
    }
    return AUX_SERVICE_NAME_SPLITTER.splitToList(auxServiceNames).stream()
        .anyMatch(name -> SCIM_AUX_SERVICE_NAME.equalsIgnoreCase(name));
  }
}
