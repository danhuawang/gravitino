/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.basic.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.google.common.collect.ImmutableMap;
import java.security.Principal;
import org.apache.gravitino.Config;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestScimOAuthPrincipalMapper {

  @AfterEach
  void tearDown() {
    ScimOAuthRequestPathFilter.clear();
  }

  @Test
  void testMapNoContext() {
    ScimOAuthPrincipalMapper principalMapper = new ScimOAuthPrincipalMapper();
    principalMapper.initialize(oauthConfig());

    Principal principal = principalMapper.map("alice");
    assertTrue(principal instanceof UserPrincipal);
    assertEquals("alice", principal.getName());
    assertTrue(((UserPrincipal) principal).getGroups().isEmpty());
  }

  @Test
  void testMapResolvesGroups() {
    ScimUserGroupRelManager membershipManager = Mockito.mock(ScimUserGroupRelManager.class);
    Mockito.when(membershipManager.listGroupNamesForUser("ml1", "alice"))
        .thenReturn(java.util.List.of("engineering", "platform"));

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(() -> membershipManager);
    principalMapper.initialize(oauthConfig());
    ScimOAuthRequestPathFilter.bind("/api/metalakes/ml1/catalogs");

    UserPrincipal userPrincipal = (UserPrincipal) principalMapper.map("alice");
    assertEquals("alice", userPrincipal.getName());
    assertEquals(2, userPrincipal.getGroups().size());
    assertTrue(
        userPrincipal.getGroups().stream()
            .anyMatch(group -> "engineering".equals(group.getGroupName())));
    assertTrue(
        userPrincipal.getGroups().stream()
            .anyMatch(group -> "platform".equals(group.getGroupName())));
  }

  @Test
  void testMapNonMetalakePath() {
    ScimUserGroupRelManager membershipManager = Mockito.mock(ScimUserGroupRelManager.class);
    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(() -> membershipManager);
    principalMapper.initialize(oauthConfig());
    ScimOAuthRequestPathFilter.bind("/api/version");

    UserPrincipal userPrincipal = (UserPrincipal) principalMapper.map("alice");
    assertEquals("alice", userPrincipal.getName());
    assertTrue(userPrincipal.getGroups().isEmpty());
  }

  private static Config oauthConfig() {
    Config config = new Config(false) {};
    config.loadFromMap(
        ImmutableMap.of(OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN.getKey(), "^(.*)$"),
        key -> true);
    return config;
  }
}
