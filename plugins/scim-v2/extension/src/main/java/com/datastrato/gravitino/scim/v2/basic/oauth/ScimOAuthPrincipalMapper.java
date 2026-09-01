/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.basic.oauth;

import com.datastrato.gravitino.scim.v2.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.v2.storage.service.ScimUserMetaService;
import java.security.Principal;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.UserGroup;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.PrincipalMapper;
import org.apache.gravitino.auth.RegexPrincipalMapper;
import org.apache.gravitino.server.authentication.OAuthConfig;

/** Resolves OAuth authorization groups from SCIM v2 membership. */
public class ScimOAuthPrincipalMapper implements PrincipalMapper {
  public static final String PRINCIPAL_MAPPER_CLASS_NAME = ScimOAuthPrincipalMapper.class.getName();

  private final Supplier<ScimUserGroupRelManager> membershipManagerSupplier;
  private final Supplier<ScimUserMetaService> userMetaServiceSupplier;
  private RegexPrincipalMapper regexPrincipalMapper;

  public ScimOAuthPrincipalMapper() {
    this(ScimUserGroupRelManager::getInstance, ScimUserMetaService::getInstance);
  }

  ScimOAuthPrincipalMapper(
      Supplier<ScimUserGroupRelManager> membershipManagerSupplier,
      Supplier<ScimUserMetaService> userMetaServiceSupplier) {
    this.membershipManagerSupplier = membershipManagerSupplier;
    this.userMetaServiceSupplier = userMetaServiceSupplier;
  }

  @Override
  public void initialize(Config config) {
    this.regexPrincipalMapper =
        new RegexPrincipalMapper(config.get(OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN));
  }

  @Override
  public Principal map(String principal) {
    if (StringUtils.isBlank(principal) || regexPrincipalMapper == null) {
      throw new IllegalArgumentException("Principal must not be blank");
    }
    String username = regexPrincipalMapper.map(principal).getName();
    var user = userMetaServiceSupplier.get().getScimUserByUserName(username);
    if (user == null || !user.isEnabled()) {
      return new UserPrincipal(username);
    }
    List<UserGroup> groups =
        membershipManagerSupplier.get().listGroupNamesForUser(username).stream()
            .map(groupName -> new UserGroup(java.util.Optional.empty(), groupName))
            .toList();
    return new UserPrincipal(username, groups);
  }
}
