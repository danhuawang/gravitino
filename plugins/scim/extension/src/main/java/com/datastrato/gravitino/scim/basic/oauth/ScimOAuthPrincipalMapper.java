/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.basic.oauth;

import com.datastrato.gravitino.scim.JitIdentityManager;
import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.storage.service.ScimUserMetaService;
import com.google.common.base.Splitter;
import com.nimbusds.jwt.SignedJWT;
import java.security.Principal;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.UserGroup;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.GroupMapper;
import org.apache.gravitino.auth.GroupMapperFactory;
import org.apache.gravitino.auth.PrincipalMapper;
import org.apache.gravitino.auth.RegexPrincipalMapper;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves OAuth authorization groups from SCIM membership and records non-SCIM identities as JIT.
 */
public class ScimOAuthPrincipalMapper implements PrincipalMapper {
  /** Fully qualified class name for {@code gravitino.authenticator.oauth.principalMapper}. */
  public static final String PRINCIPAL_MAPPER_CLASS_NAME = ScimOAuthPrincipalMapper.class.getName();

  static final String JIT_GROUPS_FIELDS_KEY = "gravitino.scim.jit.groupsFields";
  static final String DEFAULT_JIT_GROUPS_FIELD = "groups";

  private static final Logger LOG = LoggerFactory.getLogger(ScimOAuthPrincipalMapper.class);
  private static final Splitter GROUP_FIELDS_SPLITTER =
      Splitter.on(',').omitEmptyStrings().trimResults();

  private final Supplier<ScimUserGroupRelManager> membershipManagerSupplier;
  private final Supplier<ScimUserMetaService> userMetaServiceSupplier;
  private final Supplier<JitIdentityManager> jitIdentityManagerSupplier;

  private RegexPrincipalMapper regexPrincipalMapper;
  private GroupMapper groupMapper;
  private List<String> jitGroupsFields;

  /** Creates a mapper backed by the running Gravitino server environment. */
  public ScimOAuthPrincipalMapper() {
    this(
        ScimUserGroupRelManager::getInstance,
        ScimUserMetaService::getInstance,
        JitIdentityManager::getInstance);
  }

  ScimOAuthPrincipalMapper(
      Supplier<ScimUserGroupRelManager> membershipManagerSupplier,
      Supplier<ScimUserMetaService> userMetaServiceSupplier,
      Supplier<JitIdentityManager> jitIdentityManagerSupplier) {
    this.membershipManagerSupplier = membershipManagerSupplier;
    this.userMetaServiceSupplier = userMetaServiceSupplier;
    this.jitIdentityManagerSupplier = jitIdentityManagerSupplier;
  }

  @Override
  public void initialize(Config config) {
    this.regexPrincipalMapper =
        new RegexPrincipalMapper(config.get(OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN));
    this.groupMapper =
        GroupMapperFactory.create(
            config.get(OAuthConfig.GROUP_MAPPER),
            config.get(OAuthConfig.GROUP_MAPPER_REGEX_PATTERN),
            config);
    Map<String, String> jitConfig = config.getConfigsWithPrefix("gravitino.scim.jit.");
    this.jitGroupsFields =
        GROUP_FIELDS_SPLITTER.splitToList(
            jitConfig.getOrDefault("groupsFields", DEFAULT_JIT_GROUPS_FIELD));
  }

  /**
   * Maps a validated OAuth principal.
   *
   * <p>An active SCIM user receives SCIM-authoritative groups. A disabled SCIM user receives no
   * groups and is never recreated as JIT. If SCIM has no user row, groups are read from the already
   * validated JWT captured by {@link ScimOAuthRequestPathFilter}, and the identity snapshot is
   * recorded asynchronously in the global JIT tables.
   */
  @Override
  public Principal map(String principal) {
    if (StringUtils.isBlank(principal) || regexPrincipalMapper == null) {
      throw new IllegalArgumentException("Principal must not be blank");
    }

    String username = regexPrincipalMapper.map(principal).getName();
    var user = userMetaServiceSupplier.get().getScimUserByUserName(username);
    if (user == null) {
      List<UserGroup> jwtGroups = extractJwtGroups();
      jitIdentityManagerSupplier.get().recordAsync(username, jwtGroups);
      return new UserPrincipal(username, jwtGroups);
    }
    if (!user.isEnabled()) {
      return new UserPrincipal(username);
    }

    List<UserGroup> groups =
        membershipManagerSupplier.get().listGroupNamesForUser(username).stream()
            .map(groupName -> new UserGroup(java.util.Optional.empty(), groupName))
            .toList();
    return new UserPrincipal(username, groups);
  }

  private List<UserGroup> extractJwtGroups() {
    if (jitGroupsFields.isEmpty()) {
      return List.of();
    }
    return ScimOAuthRequestPathFilter.currentBearerToken()
        .map(this::extractJwtGroups)
        .orElseGet(List::of);
  }

  private List<UserGroup> extractJwtGroups(String token) {
    try {
      var claims = SignedJWT.parse(token).getJWTClaimsSet();
      for (String field : jitGroupsFields) {
        Object groups = claims.getClaim(field);
        if (groups instanceof List<?> groupClaims) {
          return groupMapper.map(groupClaims.stream().map(value -> (Object) value).toList());
        }
      }
    } catch (ParseException | IllegalArgumentException e) {
      LOG.warn("Unable to read JIT groups from the validated JWT", e);
    }
    return List.of();
  }
}
