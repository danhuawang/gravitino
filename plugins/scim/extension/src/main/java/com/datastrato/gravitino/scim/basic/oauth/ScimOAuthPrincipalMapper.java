/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.basic.oauth;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.UserGroup;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.PrincipalMapper;
import org.apache.gravitino.auth.RegexPrincipalMapper;
import org.apache.gravitino.server.authentication.OAuthConfig;

/**
 * Resolves OAuth authorization groups from SCIM membership instead of JWT claims.
 *
 * <p>Implements {@link PrincipalMapper} to map the authenticated principal to a {@link
 * UserPrincipal} with metalake-scoped membership groups from {@code user_group_rel}. JWT group
 * claim values are ignored.
 *
 * <p>{@link ScimOAuthRequestPathFilter} captures the request path before OAuth token validation so
 * {@link #map(String)} can resolve metalake from the request path. Configure {@code
 * gravitino.server.webserver.customFilters}, {@link OAuthConfig#PRINCIPAL_MAPPER}, and {@link
 * OAuthConfig#GROUPS_FIELDS} as required by {@link
 * com.datastrato.gravitino.scim.web.rest.feature.ScimTokenRESTFeature}.
 */
public class ScimOAuthPrincipalMapper implements PrincipalMapper {

  /** Fully qualified class name for {@code gravitino.authenticator.oauth.principalMapper}. */
  public static final String PRINCIPAL_MAPPER_CLASS_NAME = ScimOAuthPrincipalMapper.class.getName();

  private final Supplier<ScimUserGroupRelManager> membershipManagerSupplier;

  private RegexPrincipalMapper regexPrincipalMapper;

  /** Creates a mapper backed by the running Gravitino server environment. */
  public ScimOAuthPrincipalMapper() {
    this(ScimUserGroupRelManager::getInstance);
  }

  ScimOAuthPrincipalMapper(Supplier<ScimUserGroupRelManager> membershipManagerSupplier) {
    this.membershipManagerSupplier = membershipManagerSupplier;
  }

  @Override
  public void initialize(Config config) {
    String regexPattern = config.get(OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN);
    this.regexPrincipalMapper = new RegexPrincipalMapper(regexPattern);
  }

  /**
   * Maps an OAuth principal to a {@link UserPrincipal} with SCIM membership groups.
   *
   * <p>Uses the request path captured by {@link ScimOAuthRequestPathFilter} to scope membership
   * lookup to the target metalake.
   *
   * @param principal principal string extracted from the JWT by OAuth validation
   * @return a {@link UserPrincipal} with SCIM membership groups when context is available
   */
  @Override
  public Principal map(String principal) {
    if (StringUtils.isBlank(principal) || regexPrincipalMapper == null) {
      throw new IllegalArgumentException("Principal must not be blank");
    }

    String username = regexPrincipalMapper.map(principal).getName();
    Optional<String> metalakeName = ScimOAuthRequestPathFilter.currentMetalakeName();
    if (metalakeName.isEmpty()) {
      return new UserPrincipal(username);
    }

    List<UserGroup> groups =
        membershipManagerSupplier.get().listGroupNamesForUser(metalakeName.get(), username).stream()
            .map(groupName -> new UserGroup(Optional.empty(), groupName))
            .toList();
    return new UserPrincipal(username, groups);
  }
}
