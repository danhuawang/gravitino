/*
 * Copyright 2025 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.datastrato.gravitino.authorization.mapper.IdpNameStatusPO;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentityType;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.GroupChange;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.RoleChange;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.authorization.UserChange;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.IllegalRoleException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.RoleAlreadyExistsException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.apache.gravitino.idp.IdpUserGroupManager;
import org.apache.gravitino.idp.model.IdpGroup;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.storage.relational.service.DatastratoRoleMetaService;
import org.apache.gravitino.storage.relational.service.DatastratoUserMetaService;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatastratoAccessControlDispatcher implements AccessControlDispatcher {
  private static final Logger LOG =
      LoggerFactory.getLogger(DatastratoAccessControlDispatcher.class);
  private final AccessControlDispatcher accessControlDispatcher;
  private final EntityStore entityStore;
  private final IdpUserGroupManager idpUserGroupManager;

  /**
   * Creates the enterprise access-control dispatcher.
   *
   * @param accessControlDispatcher The wrapped OSS dispatcher.
   * @param entityStore The entity store.
   * @param idpUserGroupManager The built-in IdP user and group manager.
   */
  public DatastratoAccessControlDispatcher(
      AccessControlDispatcher accessControlDispatcher,
      EntityStore entityStore,
      IdpUserGroupManager idpUserGroupManager) {
    this.accessControlDispatcher = accessControlDispatcher;
    this.entityStore = entityStore;
    this.idpUserGroupManager = Preconditions.checkNotNull(idpUserGroupManager);
  }

  @Override
  public User addUser(String metalake, String user)
      throws UserAlreadyExistsException, NoSuchMetalakeException {
    return accessControlDispatcher.addUser(metalake, user);
  }

  @Override
  public User addUser(String metalake, String user, String externalId, boolean enabled)
      throws UserAlreadyExistsException, NoSuchMetalakeException {
    return accessControlDispatcher.addUser(metalake, user, externalId, enabled);
  }

  @Override
  public boolean removeUser(String metalake, String user) throws NoSuchMetalakeException {
    return accessControlDispatcher.removeUser(metalake, user);
  }

  @Override
  public boolean removeUserByExternalId(String metalake, String externalId)
      throws NoSuchMetalakeException {
    return accessControlDispatcher.removeUserByExternalId(metalake, externalId);
  }

  @Override
  public User getUser(String metalake, String user)
      throws NoSuchUserException, NoSuchMetalakeException {
    return accessControlDispatcher.getUser(metalake, user);
  }

  @Override
  public User getUserByExternalId(String metalake, String externalId)
      throws NoSuchUserException, NoSuchMetalakeException {
    return accessControlDispatcher.getUserByExternalId(metalake, externalId);
  }

  @Override
  public User getUserById(String metalake, long userId)
      throws NoSuchUserException, NoSuchMetalakeException {
    return accessControlDispatcher.getUserById(metalake, userId);
  }

  @Override
  public boolean removeUserById(String metalake, long userId) throws NoSuchMetalakeException {
    return accessControlDispatcher.removeUserById(metalake, userId);
  }

  @Override
  public User alterUserById(String metalake, long userId, UserChange... changes)
      throws NoSuchUserException, NoSuchMetalakeException {
    return accessControlDispatcher.alterUserById(metalake, userId, changes);
  }

  @Override
  public User[] listUsers(String metalake) throws NoSuchMetalakeException {
    return accessControlDispatcher.listUsers(metalake);
  }

  @Override
  public PagedResult<User> listUsers(String metalake, int offset, int limit)
      throws NoSuchMetalakeException {
    return accessControlDispatcher.listUsers(metalake, offset, limit);
  }

  @Override
  public long countUsers(String metalake) throws NoSuchMetalakeException {
    return accessControlDispatcher.countUsers(metalake);
  }

  @Override
  public String[] listUserNames(String metalake) throws NoSuchMetalakeException {
    return accessControlDispatcher.listUserNames(metalake);
  }

  @Override
  public Group addGroup(String metalake, String group)
      throws GroupAlreadyExistsException, NoSuchMetalakeException {
    return accessControlDispatcher.addGroup(metalake, group);
  }

  @Override
  public Group addGroup(String metalake, String group, String externalId)
      throws GroupAlreadyExistsException, NoSuchMetalakeException {
    return accessControlDispatcher.addGroup(metalake, group, externalId);
  }

  @Override
  public boolean removeGroup(String metalake, String group) throws NoSuchMetalakeException {
    return accessControlDispatcher.removeGroup(metalake, group);
  }

  @Override
  public boolean removeGroupByExternalId(String metalake, String externalId)
      throws NoSuchMetalakeException {
    return accessControlDispatcher.removeGroupByExternalId(metalake, externalId);
  }

  @Override
  public Group getGroup(String metalake, String group)
      throws NoSuchGroupException, NoSuchMetalakeException {
    return accessControlDispatcher.getGroup(metalake, group);
  }

  @Override
  public Group getGroupByExternalId(String metalake, String externalId)
      throws NoSuchGroupException, NoSuchMetalakeException {
    return accessControlDispatcher.getGroupByExternalId(metalake, externalId);
  }

  @Override
  public Group getGroupById(String metalake, long groupId)
      throws NoSuchGroupException, NoSuchMetalakeException {
    return accessControlDispatcher.getGroupById(metalake, groupId);
  }

  @Override
  public boolean removeGroupById(String metalake, long groupId) throws NoSuchMetalakeException {
    return accessControlDispatcher.removeGroupById(metalake, groupId);
  }

  @Override
  public Group alterGroupById(String metalake, long groupId, GroupChange... changes)
      throws NoSuchGroupException, NoSuchMetalakeException {
    return accessControlDispatcher.alterGroupById(metalake, groupId, changes);
  }

  @Override
  public Group[] listGroups(String metalake) {
    return accessControlDispatcher.listGroups(metalake);
  }

  @Override
  public PagedResult<Group> listGroups(String metalake, int offset, int limit) {
    return accessControlDispatcher.listGroups(metalake, offset, limit);
  }

  @Override
  public long countGroups(String metalake) {
    return accessControlDispatcher.countGroups(metalake);
  }

  @Override
  public String[] listGroupNames(String metalake) {
    return accessControlDispatcher.listGroupNames(metalake);
  }

  @Override
  public User grantRolesToUser(String metalake, List<String> roles, String user)
      throws NoSuchUserException, IllegalRoleException, NoSuchMetalakeException {
    return accessControlDispatcher.grantRolesToUser(metalake, roles, user);
  }

  @Override
  public Group grantRolesToGroup(String metalake, List<String> roles, String group)
      throws NoSuchGroupException, IllegalRoleException, NoSuchMetalakeException {
    return accessControlDispatcher.grantRolesToGroup(metalake, roles, group);
  }

  @Override
  public Group revokeRolesFromGroup(String metalake, List<String> roles, String group)
      throws NoSuchGroupException, IllegalRoleException, NoSuchMetalakeException {
    return accessControlDispatcher.revokeRolesFromGroup(metalake, roles, group);
  }

  @Override
  public User revokeRolesFromUser(String metalake, List<String> roles, String user)
      throws NoSuchUserException, IllegalRoleException, NoSuchMetalakeException {
    return accessControlDispatcher.revokeRolesFromUser(metalake, roles, user);
  }

  @Override
  public boolean isServiceAdmin(String user) {
    return accessControlDispatcher.isServiceAdmin(user);
  }

  @Override
  public Role createRole(
      String metalake,
      String role,
      Map<String, String> properties,
      List<SecurableObject> securableObjects)
      throws RoleAlreadyExistsException, NoSuchMetalakeException {
    return accessControlDispatcher.createRole(metalake, role, properties, securableObjects);
  }

  @Override
  public Role getRole(String metalake, String role)
      throws NoSuchRoleException, NoSuchMetalakeException {
    return accessControlDispatcher.getRole(metalake, role);
  }

  @Override
  public boolean deleteRole(String metalake, String role) throws NoSuchMetalakeException {
    return accessControlDispatcher.deleteRole(metalake, role);
  }

  @Override
  public Role overridePrivilegesInRole(
      String metalake, String role, List<SecurableObject> securableObjectsToOverride)
      throws NoSuchRoleException, NoSuchMetalakeException {
    return accessControlDispatcher.overridePrivilegesInRole(
        metalake, role, securableObjectsToOverride);
  }

  @Override
  public String[] listRoleNames(String metalake) throws NoSuchMetalakeException {
    return accessControlDispatcher.listRoleNames(metalake);
  }

  /**
   * Batch-updates the {@code enabled} flag for the given users under a metalake.
   *
   * <p>Validates first that every distinct username exists and has no {@code externalId}. Only then
   * runs the UPDATE. Otherwise no rows are updated and an {@link IllegalArgumentException} is
   * thrown.
   *
   * @param metalake The metalake name.
   * @param usernames User names to update.
   * @param enabled Target enabled value.
   * @return Distinct user names that were updated.
   * @throws IllegalArgumentException If any user is missing or has an external id.
   */
  public List<String> batchUpdateUserEnabled(
      String metalake, List<String> usernames, boolean enabled) {
    return DatastratoUserMetaService.getInstance()
        .batchUpdateUserEnabled(metalake, usernames, enabled);
  }

  /**
   * Lists users under a metalake with roles, group names, and built-in IdP membership in one SQL
   * query.
   *
   * @param metalake The metalake name.
   * @return Users with group names.
   */
  public List<UserWithGroups> listUsersWithGroups(String metalake) {
    return DatastratoUserMetaService.getInstance().listUsersWithGroups(metalake);
  }

  /**
   * Looks up group names for a user before adding the user into a metalake.
   *
   * @param username The username.
   * @param type The identity type.
   * @return Group names for the user.
   */
  public List<String> lookupUserGroupNames(String username, IdentityType type) {
    Preconditions.checkArgument(StringUtils.isNotBlank(username), "username cannot be blank");
    Preconditions.checkNotNull(type, "type cannot be null");

    switch (type) {
      case LOCAL:
        return idpUserGroupManager.getUser(username).groupNames();
      case PROVISIONED:
        // TODO: resolve group names for provisioned users from global SCIM identity store.
        return List.of();
      default:
        throw new IllegalArgumentException("Unsupported identity type: " + type);
    }
  }

  /**
   * Looks up group metadata before adding the group into a metalake.
   *
   * @param groupName The group name.
   * @param type The identity type.
   * @return Group metadata.
   */
  public GroupLookupInfo lookupGroupInfo(String groupName, IdentityType type) {
    Preconditions.checkArgument(StringUtils.isNotBlank(groupName), "groupName cannot be blank");
    Preconditions.checkNotNull(type, "type cannot be null");

    switch (type) {
      case LOCAL:
        IdpGroup group = idpUserGroupManager.getGroup(groupName);
        return new GroupLookupInfo(group.name(), group.comment(), group.usernames());
      case PROVISIONED:
        // TODO: resolve group metadata for provisioned groups from global SCIM identity store.
        return new GroupLookupInfo(groupName, "", List.of());
      default:
        throw new IllegalArgumentException("Unsupported identity type: " + type);
    }
  }

  /**
   * Adds a local user into a metalake.
   *
   * <p>Requires the username to already exist in the built-in IdP. Does not create login
   * credentials. Optionally grants metalake roles after the user is created.
   *
   * @param metalake The metalake name.
   * @param username The username.
   * @param roles Optional metalake roles to grant; {@code null} or empty means none.
   * @param enabled Whether the metalake user is enabled; {@code null} means enabled.
   * @return The metalake user.
   * @throws NotFoundException If the built-in IdP user does not exist.
   * @throws UserAlreadyExistsException If the metalake user already exists.
   */
  public User addLocalUser(String metalake, String username, List<String> roles, Boolean enabled) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(username), "username cannot be blank");

    idpUserGroupManager.getUser(username);

    boolean isEnabled = enabled == null || enabled;
    User user = accessControlDispatcher.addUser(metalake, username, null, isEnabled);
    if (roles != null && !roles.isEmpty()) {
      user = accessControlDispatcher.grantRolesToUser(metalake, roles, username);
    }
    return user;
  }

  /**
   * Adds a local group into a metalake.
   *
   * <p>Requires the group name to already exist in the built-in IdP. Does not create a built-in IdP
   * group. Optionally grants metalake roles after the metalake group is created.
   *
   * @param metalake The metalake name.
   * @param groupName The group name.
   * @param roles Optional metalake roles to grant; {@code null} or empty means none.
   * @return The metalake group.
   * @throws NotFoundException If the built-in IdP group does not exist.
   * @throws GroupAlreadyExistsException If the metalake group already exists.
   */
  public Group addLocalGroup(String metalake, String groupName, List<String> roles) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(groupName), "group name cannot be blank");

    idpUserGroupManager.getGroup(groupName);

    Group group = accessControlDispatcher.addGroup(metalake, groupName);
    if (roles != null && !roles.isEmpty()) {
      group = accessControlDispatcher.grantRolesToGroup(metalake, roles, groupName);
    }
    return group;
  }

  /**
   * Lists built-in IdP users and whether each is already added to the metalake.
   *
   * <p>One JOIN from {@code metalake_meta} to {@code idp_user_meta} and {@code user_meta}.
   *
   * @param metalake The metalake name.
   * @return IdP usernames with metalake membership status.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  public IdpNameStatus[] listIdpUsers(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    List<IdpNameStatusPO> rows =
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class, mapper -> mapper.listUsersWithMetalakeStatus(metalake));
    return IdpNameStatus.fromJoinResult(IdpNameStatusPO.toStatuses(rows), metalake);
  }

  /**
   * Lists built-in IdP groups and whether each is already added to the metalake.
   *
   * <p>One JOIN from {@code metalake_meta} to {@code idp_group_meta} and {@code group_meta}.
   *
   * @param metalake The metalake name.
   * @return IdP group names with metalake membership status.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  public IdpNameStatus[] listIdpGroups(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    List<IdpNameStatusPO> rows =
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class,
            mapper -> mapper.listGroupsWithMetalakeStatus(metalake));
    return IdpNameStatus.fromJoinResult(IdpNameStatusPO.toStatuses(rows), metalake);
  }

  /**
   * Loads metalake user totals split by {@code enabled} in one query against {@code user_meta}.
   *
   * @param metalake The metalake name.
   * @return User enabled counts.
   */
  public UserEnabledCounts countUsersByEnabled(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    return toUserEnabledCounts(
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class,
            mapper -> mapper.countUsersByEnabledByMetalake(metalake)));
  }

  /**
   * Loads metalake group totals and empty-group count in one query against {@code group_meta}.
   *
   * @param metalake The metalake name.
   * @return Group membership counts.
   */
  public GroupMembershipCounts countGroupsWithEmpty(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    return toGroupMembershipCounts(
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class,
            mapper -> mapper.countGroupsWithEmptyByMetalake(metalake)));
  }

  private static UserEnabledCounts toUserEnabledCounts(IdpNameStatusPO.UserEnabledCountsRow row) {
    if (row == null) {
      return UserEnabledCounts.empty();
    }
    return new UserEnabledCounts(
        zeroIfNull(row.getTotal()), zeroIfNull(row.getActive()), zeroIfNull(row.getSuspended()));
  }

  private static GroupMembershipCounts toGroupMembershipCounts(
      IdpNameStatusPO.GroupMembershipCountsRow row) {
    if (row == null) {
      return GroupMembershipCounts.zero();
    }
    return new GroupMembershipCounts(zeroIfNull(row.getTotal()), zeroIfNull(row.getEmpty()));
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  /**
   * Gets a metalake user with roles and {@code origin} in one JOIN to {@code idp_user_meta}.
   *
   * @param metalake The metalake name.
   * @param username The username.
   * @return Extended user DTO for the security UI.
   * @throws NoSuchUserException If the metalake user does not exist.
   */
  public ExtendedUserDTO getExtendedUser(String metalake, String username) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(username), "username cannot be blank");
    IdpNameStatusPO.UserWithOrigin row =
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class,
            mapper -> mapper.getUserByMetalakeWithOrigin(metalake, username));
    if (row == null || row.getUserId() == null) {
      throw new NoSuchUserException(
          "User %s does not exist in the metalake %s", username, metalake);
    }
    return IdpNameStatusPO.toExtendedUser(row, metalake);
  }

  /**
   * Lists metalake users with roles and {@code origin} in one JOIN to {@code idp_user_meta}.
   *
   * @param metalake The metalake name.
   * @return Extended user DTOs for the security UI.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  public ExtendedUserDTO[] listExtendedUsers(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    List<IdpNameStatusPO.UserWithOrigin> rows =
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class,
            mapper -> mapper.listUsersByMetalakeWithOrigin(metalake));
    return IdpNameStatusPO.toExtendedUsersByMetalake(metalake, rows);
  }

  /**
   * Lists metalake groups with roles and {@code origin} in one JOIN to {@code idp_group_meta}.
   *
   * @param metalake The metalake name.
   * @return Extended group DTOs for the security UI.
   * @throws NoSuchMetalakeException If the metalake does not exist.
   */
  public ExtendedGroupDTO[] listExtendedGroups(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    List<IdpNameStatusPO.GroupWithOrigin> rows =
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class,
            mapper -> mapper.listGroupsByMetalakeWithOrigin(metalake));
    return IdpNameStatusPO.toExtendedGroupsByMetalake(metalake, rows);
  }

  /**
   * Lists metalake groups the user belongs to, including {@code origin}.
   *
   * <p>Local users ({@code externalId} blank) use built-in IdP membership. Provisioned users use
   * SCIM membership. Names that are not metalake groups are skipped.
   *
   * @param metalake The metalake name.
   * @param username The username.
   * @return Metalake groups the user belongs to.
   * @throws NoSuchUserException If the metalake user does not exist.
   */
  public ExtendedGroupDTO[] listExtendedGroupsForUser(String metalake, String username) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(username), "username cannot be blank");
    List<IdpNameStatusPO.GroupWithOrigin> rows =
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class,
            mapper -> mapper.listGroupsForMetalakeUserWithOrigin(metalake, username));
    return IdpNameStatusPO.toExtendedGroupsForMetalakeUser(metalake, username, rows);
  }

  /**
   * Lists metalake groups the user belongs to.
   *
   * <p>Local users ({@code externalId} blank) use built-in IdP membership. Provisioned users use
   * SCIM membership. Names that are not metalake groups are skipped.
   *
   * @param metalake The metalake name.
   * @param username The username.
   * @return Metalake groups the user belongs to.
   * @throws NoSuchUserException If the metalake user does not exist.
   */
  public Group[] listGroupsForUser(String metalake, String username) {
    return listExtendedGroupsForUser(metalake, username);
  }

  /**
   * Lists metalake users that belong to the group, including {@code origin}.
   *
   * <p>Local groups ({@code externalId} blank) use built-in IdP membership. Provisioned groups use
   * SCIM membership. Names that are not metalake users are skipped.
   *
   * @param metalake The metalake name.
   * @param groupName The group name.
   * @return Metalake users in the group.
   * @throws NoSuchGroupException If the metalake group does not exist.
   */
  public ExtendedUserDTO[] listExtendedUsersForGroup(String metalake, String groupName) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(groupName), "group name cannot be blank");
    List<IdpNameStatusPO.UserWithOrigin> rows =
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class,
            mapper -> mapper.listUsersForMetalakeGroupWithOrigin(metalake, groupName));
    return IdpNameStatusPO.toExtendedUsersForMetalakeGroup(metalake, groupName, rows);
  }

  /**
   * Lists metalake users that belong to the group.
   *
   * <p>Local groups ({@code externalId} blank) use built-in IdP membership. Provisioned groups use
   * SCIM membership. Names that are not metalake users are skipped.
   *
   * @param metalake The metalake name.
   * @param groupName The group name.
   * @return Metalake users in the group.
   * @throws NoSuchGroupException If the metalake group does not exist.
   */
  public User[] listUsersForGroup(String metalake, String groupName) {
    return listExtendedUsersForGroup(metalake, groupName);
  }

  /**
   * Lists users that are granted the role under a metalake.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The users that are granted the role.
   */
  public User[] listUsersByRole(String metalake, String role) {
    accessControlDispatcher.getRole(metalake, role);
    try {
      return entityStore
          .relationOperations()
          .listEntitiesByRelation(
              SupportsRelationOperations.Type.ROLE_USER_REL,
              AuthorizationUtils.ofRole(metalake, role),
              Entity.EntityType.ROLE,
              false /* allFields */)
          .stream()
          .map(entity -> (UserEntity) entity)
          .toArray(User[]::new);
    } catch (IOException ioe) {
      LOG.error(
          "Listing users by role {} under metalake {} failed due to storage issues",
          role,
          metalake,
          ioe);
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Lists groups that are granted the role under a metalake.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The groups that are granted the role.
   */
  public Group[] listGroupsByRole(String metalake, String role) {
    accessControlDispatcher.getRole(metalake, role);
    try {
      return entityStore
          .relationOperations()
          .listEntitiesByRelation(
              SupportsRelationOperations.Type.ROLE_GROUP_REL,
              AuthorizationUtils.ofRole(metalake, role),
              Entity.EntityType.ROLE,
              false /* allFields */)
          .stream()
          .map(entity -> (GroupEntity) entity)
          .toArray(Group[]::new);
    } catch (IOException ioe) {
      LOG.error(
          "Listing groups by role {} under metalake {} failed due to storage issues",
          role,
          metalake,
          ioe);
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Lists roles with their securable objects under a metalake.
   *
   * @param metalake The metalake name.
   * @return The roles with their securable objects.
   */
  public Role[] listRolesWithSecurableObjects(String metalake) {
    Namespace namespace = AuthorizationUtils.ofRoleNamespace(metalake);
    return DatastratoRoleMetaService.getInstance()
        .listRolesWithSecurableObjectsByNamespace(namespace)
        .stream()
        .toArray(Role[]::new);
  }

  @Override
  public String[] listRoleNamesByObject(String metalake, MetadataObject object)
      throws NoSuchMetalakeException, NoSuchMetadataObjectException {
    return accessControlDispatcher.listRoleNamesByObject(metalake, object);
  }

  @Override
  public Role grantPrivilegeToRole(
      String metalake, String role, MetadataObject object, Set<Privilege> privileges)
      throws NoSuchMetalakeException, NoSuchRoleException {
    return accessControlDispatcher.grantPrivilegeToRole(metalake, role, object, privileges);
  }

  @Override
  public Role revokePrivilegesFromRole(
      String metalake, String role, MetadataObject object, Set<Privilege> privileges)
      throws NoSuchMetalakeException, NoSuchRoleException {
    return accessControlDispatcher.grantPrivilegeToRole(metalake, role, object, privileges);
  }

  // TODO: Add event dispatcher
  public Role updatePrivilegesForRole(
      String metalake, String role, List<SecurableObject> updateObjects) {
    return TreeLockUtils.doWithTreeLock(
        AuthorizationUtils.ofRole(metalake, role),
        LockType.WRITE,
        () -> {
          try {
            AuthorizationPluginCallbackWrapper authorizationCallbackWrapper =
                new AuthorizationPluginCallbackWrapper();
            Role updatedRole =
                entityStore.update(
                    AuthorizationUtils.ofRole(metalake, role),
                    RoleEntity.class,
                    Entity.EntityType.ROLE,
                    roleEntity -> {
                      List<SecurableObject> currentSecurableObjects =
                          Lists.newArrayList(roleEntity.securableObjects());

                      // This is used for recording the original state of the role before update.
                      // We use this to find which objects existed before update
                      Map<MetadataObject, SecurableObject> originObjectMap = Maps.newHashMap();
                      for (SecurableObject securableObject : currentSecurableObjects) {
                        originObjectMap.put(
                            MetadataObjects.parse(
                                securableObject.fullName(), securableObject.type()),
                            securableObject);
                      }

                      // This is used for recording the updated state of the role after update.
                      Map<MetadataObject, SecurableObject> updatedObjectMap = Maps.newHashMap();
                      for (SecurableObject securableObject : updateObjects) {
                        updatedObjectMap.put(
                            MetadataObjects.parse(
                                securableObject.fullName(), securableObject.type()),
                            securableObject);
                      }

                      // These sets will be used for tracking which objects are created, updated or
                      // deleted.
                      Set<MetadataObject> authzPluginCreatedObjects =
                          Sets.newHashSet(updatedObjectMap.keySet());
                      authzPluginCreatedObjects.removeAll(originObjectMap.keySet());

                      Set<MetadataObject> authzPluginUpdateObjects =
                          Sets.newHashSet(updatedObjectMap.keySet());
                      authzPluginUpdateObjects.retainAll(originObjectMap.keySet());

                      Set<MetadataObject> authzPluginDeletedObjects =
                          Sets.newHashSet(originObjectMap.keySet());
                      authzPluginDeletedObjects.removeAll(updatedObjectMap.keySet());

                      // We set authorization callback here, we won't execute this callback in this
                      // place. We will execute the callback after we execute the SQL transaction.
                      authorizationCallbackWrapper.setCallback(
                          () -> {
                            authzPluginCreatedObjects.forEach(
                                object -> {
                                  AuthorizationUtils.callAuthorizationPluginForMetadataObject(
                                      metalake,
                                      object,
                                      authorizationPlugin -> {
                                        authorizationPlugin.onRoleUpdated(
                                            roleEntity,
                                            RoleChange.addSecurableObject(
                                                role, updatedObjectMap.get(object)));
                                      });
                                });
                            authzPluginUpdateObjects.forEach(
                                object -> {
                                  SecurableObject existingObject = originObjectMap.get(object);
                                  SecurableObject newSecurableObject = updatedObjectMap.get(object);
                                  // If the updated role is the same as the existing one, we don't
                                  // need to call the authorization plugin.
                                  if (existingObject != null
                                      && newSecurableObject != null
                                      && !existingObject
                                          .privileges()
                                          .equals(newSecurableObject.privileges())) {
                                    AuthorizationUtils.callAuthorizationPluginForMetadataObject(
                                        metalake,
                                        object,
                                        authorizationPlugin -> {
                                          authorizationPlugin.onRoleUpdated(
                                              roleEntity,
                                              RoleChange.updateSecurableObject(
                                                  role, existingObject, newSecurableObject));
                                        });
                                  }
                                });
                            authzPluginDeletedObjects.forEach(
                                object -> {
                                  AuthorizationUtils.callAuthorizationPluginForMetadataObject(
                                      metalake,
                                      object,
                                      authorizationPlugin -> {
                                        authorizationPlugin.onRoleUpdated(
                                            roleEntity,
                                            RoleChange.removeSecurableObject(
                                                role, originObjectMap.get(object)));
                                      });
                                });
                          });

                      AuditInfo auditInfo =
                          AuditInfo.builder()
                              .withCreator(roleEntity.auditInfo().creator())
                              .withCreateTime(roleEntity.auditInfo().createTime())
                              .withLastModifier(PrincipalUtils.getCurrentPrincipal().getName())
                              .withLastModifiedTime(Instant.now())
                              .build();

                      return RoleEntity.builder()
                          .withId(roleEntity.id())
                          .withName(roleEntity.name())
                          .withNamespace(roleEntity.namespace())
                          .withProperties(roleEntity.properties())
                          .withAuditInfo(auditInfo)
                          .withSecurableObjects(Lists.newArrayList(updatedObjectMap.values()))
                          .build();
                    });
            authorizationCallbackWrapper.execute();

            return updatedRole;
          } catch (NoSuchEntityException nse) {
            LOG.error(
                "Failed to update role {}, because the role does not exist in the metalake {}",
                role,
                metalake,
                nse);
            throw new NoSuchRoleException(
                "Role %s does not exist in the metalake %s", role, metalake);
          } catch (IOException ioe) {
            throw new RuntimeException(ioe);
          }
        });
  }

  private static class AuthorizationPluginCallbackWrapper {
    private Runnable callback;

    public void setCallback(Runnable callback) {
      this.callback = callback;
    }

    public void execute() {
      if (callback != null) {
        callback.run();
      }
    }
  }
}
