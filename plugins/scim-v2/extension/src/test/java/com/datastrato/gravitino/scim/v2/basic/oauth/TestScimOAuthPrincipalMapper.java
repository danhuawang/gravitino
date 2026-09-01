/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.basic.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.v2.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.v2.model.ScimUserMeta;
import com.datastrato.gravitino.scim.v2.storage.service.ScimUserMetaService;
import com.google.common.collect.ImmutableMap;
import java.security.Principal;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestScimOAuthPrincipalMapper {

  @AfterEach
  void tearDown() {
    ScimOAuthRequestPathFilter.clear();
  }

  @Test
  void testMapUnknownUser() {
    ScimUserGroupRelManager membershipManager = mock(ScimUserGroupRelManager.class);
    ScimUserMetaService userMetaService = mock(ScimUserMetaService.class);
    when(userMetaService.getScimUserByUserName("alice")).thenReturn(null);

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(() -> membershipManager, () -> userMetaService);
    principalMapper.initialize(oauthConfig());

    Principal principal = principalMapper.map("alice");
    assertTrue(principal instanceof UserPrincipal);
    assertEquals("alice", principal.getName());
    assertTrue(((UserPrincipal) principal).getGroups().isEmpty());
  }

  @Test
  void testMapResolvesGroups() {
    ScimUserGroupRelManager membershipManager = mock(ScimUserGroupRelManager.class);
    ScimUserMetaService userMetaService = mock(ScimUserMetaService.class);
    when(userMetaService.getScimUserByUserName("alice"))
        .thenReturn(
            ScimUserMeta.builder()
                .withUserId(1L)
                .withUserName("alice")
                .withExternalId("ext-alice")
                .withEnabled(true)
                .build());
    when(membershipManager.listGroupNamesForUser("alice"))
        .thenReturn(List.of("engineering", "platform"));

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(() -> membershipManager, () -> userMetaService);
    principalMapper.initialize(oauthConfig());

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
  void testMapDisabledUser() {
    ScimUserGroupRelManager membershipManager = mock(ScimUserGroupRelManager.class);
    ScimUserMetaService userMetaService = mock(ScimUserMetaService.class);
    when(userMetaService.getScimUserByUserName("alice"))
        .thenReturn(
            ScimUserMeta.builder()
                .withUserId(1L)
                .withUserName("alice")
                .withExternalId("ext-alice")
                .withEnabled(false)
                .build());

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(() -> membershipManager, () -> userMetaService);
    principalMapper.initialize(oauthConfig());

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
