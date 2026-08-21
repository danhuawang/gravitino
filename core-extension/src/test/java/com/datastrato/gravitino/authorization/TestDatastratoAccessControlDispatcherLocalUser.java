/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License, Version 2.
 */
package com.datastrato.gravitino.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.idp.IdpUserGroupManager;
import org.apache.gravitino.idp.model.IdpGroup;
import org.apache.gravitino.idp.model.IdpUser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestDatastratoAccessControlDispatcherLocalUser {

  private static final String METALAKE = "metalake";

  private AccessControlDispatcher inner;
  private DatastratoAccessControlDispatcher dispatcher;
  private IdpUserGroupManager idp;
  private ScimUserGroupRelManager scim;

  @BeforeEach
  public void setUp() {
    inner = mock(AccessControlDispatcher.class);
    idp = mock(IdpUserGroupManager.class);
    scim = mock(ScimUserGroupRelManager.class);
    dispatcher = new DatastratoAccessControlDispatcher(inner, mock(EntityStore.class), idp, scim);
  }

  @Test
  public void testAddUser() {
    stubIdpUser("alice");
    User created = mock(User.class);
    when(inner.addUser(METALAKE, "alice", null, true)).thenReturn(created);

    Assertions.assertSame(created, dispatcher.addLocalUser(METALAKE, "alice", null, true));
    verify(inner, never()).grantRolesToUser(any(), any(), any());
  }

  @Test
  public void testAddUserMissingIdp() {
    when(idp.getUser("missing")).thenThrow(new NotFoundException("missing"));

    Assertions.assertThrows(
        NotFoundException.class, () -> dispatcher.addLocalUser(METALAKE, "missing", null, true));
    verify(inner, never()).addUser(any(), any(), any(), anyBoolean());
  }

  @Test
  public void testAddUserRoles() {
    stubIdpUser("alice");
    when(inner.addUser(METALAKE, "alice", null, true)).thenReturn(mock(User.class));
    User granted = mock(User.class);
    when(inner.grantRolesToUser(METALAKE, List.of("Analyst"), "alice")).thenReturn(granted);

    Assertions.assertSame(
        granted, dispatcher.addLocalUser(METALAKE, "alice", List.of("Analyst"), true));
  }

  @Test
  public void testAddGroup() {
    stubIdpGroup("contractors");
    Group created = mock(Group.class);
    when(inner.addGroup(METALAKE, "contractors")).thenReturn(created);

    Assertions.assertSame(created, dispatcher.addLocalGroup(METALAKE, "contractors", null));
    verify(inner, never()).grantRolesToGroup(any(), any(), any());
  }

  @Test
  public void testAddGroupMissingIdp() {
    when(idp.getGroup("missing")).thenThrow(new NotFoundException("missing"));

    Assertions.assertThrows(
        NotFoundException.class, () -> dispatcher.addLocalGroup(METALAKE, "missing", null));
    verify(inner, never()).addGroup(any(), any());
  }

  @Test
  public void testAddGroupRoles() {
    stubIdpGroup("contractors");
    when(inner.addGroup(METALAKE, "contractors")).thenReturn(mock(Group.class));
    Group granted = mock(Group.class);
    when(inner.grantRolesToGroup(METALAKE, List.of("Analyst"), "contractors")).thenReturn(granted);

    Assertions.assertSame(
        granted, dispatcher.addLocalGroup(METALAKE, "contractors", List.of("Analyst")));
  }

  @Test
  public void testListUserGroupsLocal() {
    stubMetalakeUser("alice", null);
    when(idp.getUser("alice")).thenReturn(new IdpUser("alice", List.of("g1", "missing")));
    Group g1 = mock(Group.class);
    when(inner.getGroup(METALAKE, "g1")).thenReturn(g1);
    when(inner.getGroup(METALAKE, "missing")).thenThrow(new NoSuchGroupException("missing"));

    Assertions.assertArrayEquals(new Group[] {g1}, dispatcher.listGroupsForUser(METALAKE, "alice"));
    verify(scim, never()).listGroupNamesForUser(any(), any());
  }

  @Test
  public void testListUserGroupsScim() {
    stubMetalakeUser("priya", "okta-1");
    when(scim.listGroupNamesForUser(METALAKE, "priya")).thenReturn(List.of("analysts"));
    Group analysts = mock(Group.class);
    when(inner.getGroup(METALAKE, "analysts")).thenReturn(analysts);

    Assertions.assertArrayEquals(
        new Group[] {analysts}, dispatcher.listGroupsForUser(METALAKE, "priya"));
    verify(idp, never()).getUser(any());
  }

  @Test
  public void testListGroupUsersLocal() {
    stubMetalakeGroup("contractors", "", null);
    when(idp.getGroup("contractors"))
        .thenReturn(new IdpGroup("contractors", List.of("alice", "gone")));
    User alice = mock(User.class);
    when(inner.getUser(METALAKE, "alice")).thenReturn(alice);
    when(inner.getUser(METALAKE, "gone")).thenThrow(new NoSuchUserException("gone"));

    Assertions.assertArrayEquals(
        new User[] {alice}, dispatcher.listUsersForGroup(METALAKE, "contractors"));
    verify(scim, never()).listMembersForGroup(any(), anyLong());
  }

  @Test
  public void testListGroupUsersScim() {
    stubMetalakeGroup("analysts", "okta-g1", 42L);
    when(scim.listMembersForGroup(METALAKE, 42L))
        .thenReturn(
            List.of(ScimGroupMemberPO.builder().withUserId(7L).withUserName("priya").build()));
    User priya = mock(User.class);
    when(inner.getUser(METALAKE, "priya")).thenReturn(priya);

    Assertions.assertArrayEquals(
        new User[] {priya}, dispatcher.listUsersForGroup(METALAKE, "analysts"));
    verify(idp, never()).getGroup(any());
  }

  private void stubIdpUser(String name) {
    when(idp.getUser(name)).thenReturn(new IdpUser(name, Collections.emptyList()));
  }

  private void stubIdpGroup(String name) {
    when(idp.getGroup(name)).thenReturn(new IdpGroup(name, Collections.emptyList()));
  }

  private void stubMetalakeUser(String name, String externalId) {
    User user = mock(User.class);
    when(user.externalId()).thenReturn(externalId);
    when(inner.getUser(METALAKE, name)).thenReturn(user);
  }

  private void stubMetalakeGroup(String name, String externalId, Long id) {
    Group group = mock(Group.class);
    when(group.externalId()).thenReturn(externalId);
    when(group.id()).thenReturn(id);
    when(inner.getGroup(METALAKE, name)).thenReturn(group);
  }
}
