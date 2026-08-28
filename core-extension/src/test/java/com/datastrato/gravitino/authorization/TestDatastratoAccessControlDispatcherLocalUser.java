/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.authorization.IdentityType;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.User;
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

  @BeforeEach
  public void setUp() {
    inner = mock(AccessControlDispatcher.class);
    idp = mock(IdpUserGroupManager.class);
    dispatcher = new DatastratoAccessControlDispatcher(inner, mock(EntityStore.class), idp);
  }

  @Test
  public void testLookupUserGroupNamesLocal() {
    when(idp.getUser("alice")).thenReturn(new IdpUser("alice", List.of("contractors", "analysts")));

    Assertions.assertEquals(
        List.of("contractors", "analysts"),
        dispatcher.lookupUserGroupNames("alice", IdentityType.LOCAL));
  }

  @Test
  public void testLookupUserGroupNamesProvisionedTodo() {
    Assertions.assertEquals(
        List.of(), dispatcher.lookupUserGroupNames("dana", IdentityType.PROVISIONED));
    verify(idp, never()).getUser(any());
  }

  @Test
  public void testLookupGroupInfoLocal() {
    when(idp.getGroup("contractors"))
        .thenReturn(
            new IdpGroup(
                "contractors", List.of("alice", "bob"), "External analysts on time-boxed access"));

    GroupLookupInfo info = dispatcher.lookupGroupInfo("contractors", IdentityType.LOCAL);
    Assertions.assertEquals("contractors", info.groupName());
    Assertions.assertEquals("External analysts on time-boxed access", info.comment());
    Assertions.assertEquals(List.of("alice", "bob"), info.members());
  }

  @Test
  public void testLookupGroupInfoProvisionedTodo() {
    GroupLookupInfo info = dispatcher.lookupGroupInfo("analysts", IdentityType.PROVISIONED);
    Assertions.assertEquals("analysts", info.groupName());
    Assertions.assertEquals("", info.comment());
    Assertions.assertEquals(List.of(), info.members());
    verify(idp, never()).getGroup(any());
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

  private void stubIdpUser(String name) {
    when(idp.getUser(name)).thenReturn(new IdpUser(name, Collections.emptyList()));
  }

  private void stubIdpGroup(String name) {
    when(idp.getGroup(name)).thenReturn(new IdpGroup(name, Collections.emptyList()));
  }
}
