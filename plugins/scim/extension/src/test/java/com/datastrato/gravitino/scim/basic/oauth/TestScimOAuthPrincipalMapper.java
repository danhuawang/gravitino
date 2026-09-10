/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.basic.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.JitIdentityManager;
import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.model.ScimUserMeta;
import com.datastrato.gravitino.scim.storage.service.ScimUserMetaService;
import com.google.common.collect.ImmutableMap;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
    JitIdentityManager jitIdentityManager = mock(JitIdentityManager.class);
    when(userMetaService.getScimUserByUserName("alice")).thenReturn(null);

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(
            () -> membershipManager, () -> userMetaService, () -> jitIdentityManager);
    principalMapper.initialize(oauthConfig());

    Principal principal = principalMapper.map("alice");
    assertTrue(principal instanceof UserPrincipal);
    assertEquals("alice", principal.getName());
    assertTrue(((UserPrincipal) principal).getGroups().isEmpty());
    verify(jitIdentityManager).recordAsync(eq("alice"), argThat(List::isEmpty));
  }

  @Test
  void testMapUnknownUserWithJwtGroups() throws JOSEException {
    ScimUserGroupRelManager membershipManager = mock(ScimUserGroupRelManager.class);
    ScimUserMetaService userMetaService = mock(ScimUserMetaService.class);
    JitIdentityManager jitIdentityManager = mock(JitIdentityManager.class);
    when(userMetaService.getScimUserByUserName("alice")).thenReturn(null);
    ScimOAuthRequestPathFilter.bind(
        "/api/version", "Bearer " + tokenWithGroups(List.of("engineering", "platform")));

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(
            () -> membershipManager, () -> userMetaService, () -> jitIdentityManager);
    principalMapper.initialize(oauthConfig());

    UserPrincipal principal = (UserPrincipal) principalMapper.map("alice");
    assertEquals(
        List.of("engineering", "platform"),
        principal.getGroups().stream().map(group -> group.getGroupName()).toList());
    verify(jitIdentityManager)
        .recordAsync(
            eq("alice"),
            argThat(
                groups ->
                    groups.stream()
                        .map(group -> group.getGroupName())
                        .toList()
                        .equals(List.of("engineering", "platform"))));
  }

  @Test
  void testMapResolvesGroups() {
    ScimUserGroupRelManager membershipManager = mock(ScimUserGroupRelManager.class);
    ScimUserMetaService userMetaService = mock(ScimUserMetaService.class);
    JitIdentityManager jitIdentityManager = mock(JitIdentityManager.class);
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
        new ScimOAuthPrincipalMapper(
            () -> membershipManager, () -> userMetaService, () -> jitIdentityManager);
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
    verifyNoInteractions(jitIdentityManager);
  }

  @Test
  void testMapDisabledUser() {
    ScimUserGroupRelManager membershipManager = mock(ScimUserGroupRelManager.class);
    ScimUserMetaService userMetaService = mock(ScimUserMetaService.class);
    JitIdentityManager jitIdentityManager = mock(JitIdentityManager.class);
    when(userMetaService.getScimUserByUserName("alice"))
        .thenReturn(
            ScimUserMeta.builder()
                .withUserId(1L)
                .withUserName("alice")
                .withExternalId("ext-alice")
                .withEnabled(false)
                .build());

    ScimOAuthPrincipalMapper principalMapper =
        new ScimOAuthPrincipalMapper(
            () -> membershipManager, () -> userMetaService, () -> jitIdentityManager);
    principalMapper.initialize(oauthConfig());

    UserPrincipal userPrincipal = (UserPrincipal) principalMapper.map("alice");
    assertEquals("alice", userPrincipal.getName());
    assertTrue(userPrincipal.getGroups().isEmpty());
    verifyNoInteractions(jitIdentityManager);
  }

  private static Config oauthConfig() {
    Config config = new Config(false) {};
    config.loadFromMap(
        ImmutableMap.of(OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN.getKey(), "^(.*)$"),
        key -> true);
    return config;
  }

  private static String tokenWithGroups(List<String> groups) throws JOSEException {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder().claim("groups", groups).build());
    jwt.sign(new MACSigner("01234567890123456789012345678901"));
    return jwt.serialize();
  }
}
