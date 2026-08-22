/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.DatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.search.po.SearchEntityPO.SearchRolePermissionPO;
import com.datastrato.gravitino.search.po.SearchEntityPO.SearchUserPermissionPO;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.User;

/** Caches authorization data per Metalake and projects effective grants onto search documents. */
public final class PermissionProjectionCache {
  private static final Map<String, MetalakePermissions> CACHE = new ConcurrentHashMap<>();

  private PermissionProjectionCache() {}

  /**
   * Returns the direct Role and User permission projections for a metadata object.
   *
   * @param metalake The Metalake containing the object.
   * @param object The searchable metadata object.
   * @return The projected permissions.
   */
  public static Permissions getPermissions(String metalake, MetadataObject object) {
    AccessControlDispatcher dispatcher = accessControlDispatcher();
    if (dispatcher == null) {
      return Permissions.EMPTY;
    }

    return CACHE.computeIfAbsent(metalake, ignored -> load(metalake, dispatcher)).project(object);
  }

  /**
   * Invalidates cached authorization data for a Metalake.
   *
   * @param metalake The Metalake to invalidate.
   */
  public static void invalidate(String metalake) {
    CACHE.remove(metalake);
  }

  @VisibleForTesting
  static Permissions getPermissions(
      String metalake, MetadataObject object, AccessControlDispatcher dispatcher) {
    return load(metalake, dispatcher).project(object);
  }

  private static AccessControlDispatcher accessControlDispatcher() {
    AccessControlDispatcher dispatcher =
        DatastratoGravitinoEnv.getInstance().internalAccessControlDispatcher();
    if (dispatcher != null) {
      return dispatcher;
    }

    dispatcher = GravitinoEnv.getInstance().internalAccessControlDispatcher();
    return dispatcher != null ? dispatcher : GravitinoEnv.getInstance().accessControlDispatcher();
  }

  private static MetalakePermissions load(
      String metalake, AccessControlDispatcher accessControlDispatcher) {
    List<User> users =
        accessControlDispatcher instanceof DatastratoAccessControlDispatcher
            ? Collections.emptyList()
            : Arrays.asList(accessControlDispatcher.listUsers(metalake));
    List<RoleGrant> grants = new ArrayList<>();

    Arrays.stream(accessControlDispatcher.listRoleNames(metalake))
        .sorted()
        .forEach(
            roleName -> {
              Role role = accessControlDispatcher.getRole(metalake, roleName);
              List<String> directUsers =
                  directUsers(accessControlDispatcher, users, metalake, roleName);
              if (role.securableObjects() != null) {
                role.securableObjects().stream()
                    .filter(securableObject -> securableObject.privileges() != null)
                    .forEach(
                        securableObject ->
                            grants.add(new RoleGrant(roleName, directUsers, securableObject)));
              }
            });
    return new MetalakePermissions(grants);
  }

  private static List<String> directUsers(
      AccessControlDispatcher dispatcher, List<User> users, String metalake, String roleName) {
    if (dispatcher instanceof DatastratoAccessControlDispatcher) {
      return Arrays.stream(
              ((DatastratoAccessControlDispatcher) dispatcher).listUsersByRole(metalake, roleName))
          .map(User::name)
          .sorted()
          .collect(Collectors.toList());
    }

    return users.stream()
        .filter(user -> user.roles() != null && user.roles().contains(roleName))
        .map(User::name)
        .sorted()
        .collect(Collectors.toList());
  }

  private static boolean appliesTo(SecurableObject securableObject, MetadataObject object) {
    if (securableObject.type() == MetadataObject.Type.METALAKE) {
      return true;
    }

    String securableName = securableObject.fullName();
    String objectName = object.fullName();
    return objectName.equals(securableName) || objectName.startsWith(securableName + ".");
  }

  private static class MetalakePermissions {
    private final List<RoleGrant> grants;

    private MetalakePermissions(List<RoleGrant> grants) {
      this.grants = ImmutableList.copyOf(grants);
    }

    private Permissions project(MetadataObject object) {
      Map<String, SearchRolePermissionPO> rolePermissions = new TreeMap<>();
      Map<String, SearchUserPermissionPO> userPermissions = new TreeMap<>();

      grants.stream()
          .filter(grant -> appliesTo(grant.securableObject, object))
          .forEach(
              grant -> {
                for (Privilege privilege : grant.securableObject.privileges()) {
                  if (!privilege.canBindTo(object.type())) {
                    continue;
                  }

                  String permission = privilege.simpleString();
                  String roleKey = grant.roleName + '\0' + permission;
                  rolePermissions.putIfAbsent(
                      roleKey,
                      SearchRolePermissionPO.builder()
                          .withName(grant.roleName)
                          .withPermission(permission)
                          .build());
                  for (String userName : grant.directUsers) {
                    String userKey = userName + '\0' + permission;
                    userPermissions.putIfAbsent(
                        userKey,
                        SearchUserPermissionPO.builder()
                            .withName(userName)
                            .withPermission(permission)
                            .build());
                  }
                }
              });

      return new Permissions(
          ImmutableList.copyOf(userPermissions.values()),
          ImmutableList.copyOf(rolePermissions.values()));
    }
  }

  private static class RoleGrant {
    private final String roleName;
    private final List<String> directUsers;
    private final SecurableObject securableObject;

    private RoleGrant(String roleName, List<String> directUsers, SecurableObject securableObject) {
      this.roleName = roleName;
      this.directUsers = ImmutableList.copyOf(directUsers);
      this.securableObject = securableObject;
    }
  }

  /** A pair of User and Role permission projections for one searchable object. */
  public static class Permissions {
    private static final Permissions EMPTY =
        new Permissions(ImmutableList.of(), ImmutableList.of());

    private final List<SearchUserPermissionPO> userPermissions;
    private final List<SearchRolePermissionPO> rolePermissions;

    private Permissions(
        List<SearchUserPermissionPO> userPermissions,
        List<SearchRolePermissionPO> rolePermissions) {
      this.userPermissions = userPermissions;
      this.rolePermissions = rolePermissions;
    }

    /**
     * @return Direct User permission projections.
     */
    public List<SearchUserPermissionPO> userPermissions() {
      return userPermissions;
    }

    /**
     * @return Role permission projections.
     */
    public List<SearchRolePermissionPO> rolePermissions() {
      return rolePermissions;
    }
  }
}
