/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.web.rest.feature;

import com.datastrato.gravitino.scim.v2.ScimTokenManager;
import com.datastrato.gravitino.scim.v2.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.v2.basic.oauth.ScimOAuthPrincipalMapper;
import com.datastrato.gravitino.scim.v2.basic.oauth.ScimOAuthRequestPathFilter;
import com.datastrato.gravitino.scim.v2.web.rest.ScimAuthorizationFilter;
import com.datastrato.gravitino.scim.v2.web.rest.ScimProvisioningOperations;
import com.datastrato.gravitino.scim.v2.web.rest.ScimTokenBinder;
import com.datastrato.gravitino.scim.v2.web.rest.ScimTokenOperations;
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

/** Registers SCIM v2 token admin REST resources on the main Gravitino server. */
@Provider
public class ScimTokenRESTFeature implements Feature {

  private static final Logger LOG = LoggerFactory.getLogger(ScimTokenRESTFeature.class);
  private static final Splitter AUX_SERVICE_NAME_SPLITTER =
      Splitter.on(',').omitEmptyStrings().trimResults();
  private static final String SCIM_V2_AUX_SERVICE_NAME = "scim-v2";

  /** Extension package name registered through {@code gravitino.server.rest.extensionPackages}. */
  public static final String SCIM_TOKEN_REST_EXTENSION_PACKAGE =
      ScimTokenRESTFeature.class.getPackageName();

  @Override
  public boolean configure(FeatureContext context) {
    GravitinoEnv env = GravitinoEnv.getInstance();
    Config config = env.config();
    validateConfiguration(config);

    ScimTokenManager.getInstance().initialize(config, env.idGenerator());
    ScimUserGroupRelManager.getInstance().initialize(config);

    context.register(ScimAuthorizationFilter.class);
    context.register(ScimTokenBinder.class);
    context.register(ScimTokenOperations.class);
    context.register(ScimProvisioningOperations.class);
    LOG.info(
        "SCIM v2 OAuth authorization uses v2_scim_user_group_rel via {}",
        ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME);
    LOG.info("Initialized SCIM v2 token manager for token admin REST APIs");
    return true;
  }

  static void validateConfiguration(Config config) {
    if (!isScimV2AuxServiceEnabled(config)) {
      LOG.error(
          "gravitino.server.rest.extensionPackages includes the SCIM v2 token admin plugin ({}) but "
              + "'scim-v2' is not listed in gravitino.auxService.names.",
          SCIM_TOKEN_REST_EXTENSION_PACKAGE);
      System.exit(1);
    }
    if (!isScimPrincipalMapperConfigured(config)) {
      LOG.error(
          "gravitino.authenticator.oauth.principalMapper must be {} for SCIM v2.",
          ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME);
      System.exit(1);
    }
    if (!usesEmptyGroupsFields(config)) {
      LOG.error("gravitino.authenticator.oauth.groupsFields must be empty for SCIM v2.");
      System.exit(1);
    }
    if (!isScimContextFilterConfigured(config)) {
      LOG.error(
          "gravitino.server.webserver.customFilters must include {} for SCIM v2.",
          ScimOAuthRequestPathFilter.FILTER_CLASS_NAME);
      System.exit(1);
    }
    if (!Boolean.TRUE.equals(config.get(Configs.ENABLE_AUTHORIZATION))) {
      LOG.error("gravitino.authorization.enable must be true for SCIM v2 token admin APIs.");
      System.exit(1);
    }
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
    return ScimOAuthPrincipalMapper.PRINCIPAL_MAPPER_CLASS_NAME.equals(
        config.get(OAuthConfig.PRINCIPAL_MAPPER));
  }

  private static boolean isScimV2AuxServiceEnabled(Config config) {
    String auxServiceNames =
        config
            .getConfigsWithPrefix(AuxiliaryServiceManager.GRAVITINO_AUX_SERVICE_PREFIX)
            .getOrDefault(AuxiliaryServiceManager.AUX_SERVICE_NAMES, "");
    if (StringUtils.isBlank(auxServiceNames)) {
      return false;
    }
    return AUX_SERVICE_NAME_SPLITTER.splitToList(auxServiceNames).stream()
        .anyMatch(name -> SCIM_V2_AUX_SERVICE_NAME.equalsIgnoreCase(name));
  }
}
