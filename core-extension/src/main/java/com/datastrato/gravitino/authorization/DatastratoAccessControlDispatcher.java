/*
 * Copyright 2025 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.RoleChange;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.IllegalRoleException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.RoleAlreadyExistsException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatastratoAccessControlDispatcher implements AccessControlDispatcher {
  private static final Logger LOG =
      LoggerFactory.getLogger(DatastratoAccessControlDispatcher.class);
  private final AccessControlDispatcher accessControlDispatcher;
  private final EntityStore entityStore;

  public DatastratoAccessControlDispatcher(
      AccessControlDispatcher accessControlDispatcher, EntityStore entityStore) {
    this.accessControlDispatcher = accessControlDispatcher;
    this.entityStore = entityStore;
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
  public User enableUser(String metalake, String externalId)
      throws NoSuchUserException, NoSuchMetalakeException {
    return accessControlDispatcher.enableUser(metalake, externalId);
  }

  @Override
  public User disableUser(String metalake, String externalId)
      throws NoSuchUserException, NoSuchMetalakeException {
    return accessControlDispatcher.disableUser(metalake, externalId);
  }

  @Override
  public User[] listUsers(String metalake) throws NoSuchMetalakeException {
    return accessControlDispatcher.listUsers(metalake);
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
  public boolean removeGroup(String metalake, String group) throws NoSuchMetalakeException {
    return accessControlDispatcher.removeGroup(metalake, group);
  }

  @Override
  public Group getGroup(String metalake, String group)
      throws NoSuchGroupException, NoSuchMetalakeException {
    return accessControlDispatcher.getGroup(metalake, group);
  }

  @Override
  public Group[] listGroups(String metalake) {
    return accessControlDispatcher.listGroups(metalake);
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
