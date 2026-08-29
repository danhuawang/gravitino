/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.CatalogAuthorizationDTO;
import com.datastrato.gravitino.dto.authorization.ObjectAuthorizationDTO;
import com.datastrato.gravitino.dto.authorization.RoleMembershipDTO;
import com.datastrato.gravitino.dto.authorization.RolePrivilegeDTO;
import com.datastrato.gravitino.dto.responses.AuthorizationOverviewResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.authorization.PrivilegeDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** REST operations for loading a metalake authorization overview. */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}/authorization/overview")
public class ExtendedAuthorizationOverviewOperations {

  private static final Set<Privilege.Name> WRITE_OR_ADMIN_PRIVILEGES =
      Collections.unmodifiableSet(
          EnumSet.of(
              Privilege.Name.CREATE_CATALOG,
              Privilege.Name.CREATE_SCHEMA,
              Privilege.Name.CREATE_TABLE,
              Privilege.Name.MODIFY_TABLE,
              Privilege.Name.CREATE_FILESET,
              Privilege.Name.WRITE_FILESET,
              Privilege.Name.CREATE_TOPIC,
              Privilege.Name.PRODUCE_TOPIC,
              Privilege.Name.MANAGE_USERS,
              Privilege.Name.MANAGE_GROUPS,
              Privilege.Name.CREATE_ROLE,
              Privilege.Name.MANAGE_GRANTS,
              Privilege.Name.REGISTER_MODEL,
              Privilege.Name.LINK_MODEL_VERSION,
              Privilege.Name.CREATE_TAG,
              Privilege.Name.APPLY_TAG,
              Privilege.Name.CREATE_POLICY,
              Privilege.Name.APPLY_POLICY,
              Privilege.Name.REGISTER_JOB_TEMPLATE,
              Privilege.Name.RUN_JOB,
              Privilege.Name.CREATE_VIEW,
              Privilege.Name.REGISTER_FUNCTION,
              Privilege.Name.MODIFY_FUNCTION));

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /** Creates a new authorization overview operation. */
  public ExtendedAuthorizationOverviewOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Loads objects grouped by catalog and all visible roles with their direct members.
   *
   * @param metalake The metalake name.
   * @return The authorization overview response.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "")
  public Response getAuthorizationOverview(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            Role[] roles = loadVisibleRoles(metalake);
            Map<String, Set<String>> usersByRole = initializeMembersByRole(roles);
            Map<String, Set<String>> groupsByRole = initializeMembersByRole(roles);
            collectUsersByRole(usersByRole, accessControlDispatcher.listUsers(metalake));
            collectGroupsByRole(groupsByRole, accessControlDispatcher.listGroups(metalake));

            Map<String, CatalogAccumulator> catalogs = new LinkedHashMap<>();
            Map<String, RoleAccumulator> roleAccumulators = new LinkedHashMap<>();
            Arrays.stream(roles)
                .forEach(
                    role -> {
                      int assignCount =
                          usersByRole.getOrDefault(role.name(), Collections.emptySet()).size()
                              + groupsByRole
                                  .getOrDefault(role.name(), Collections.emptySet())
                                  .size();
                      RoleAccumulator roleAccumulator =
                          new RoleAccumulator(
                              role.name(), role.auditInfo().createTime(), assignCount);
                      roleAccumulators.put(role.name(), roleAccumulator);
                      role.securableObjects()
                          .forEach(object -> collectRoleObject(catalogs, roleAccumulator, object));
                    });

            CatalogAuthorizationDTO[] catalogDTOs =
                catalogs.values().stream()
                    .map(catalog -> buildCatalogDTO(catalog, usersByRole, groupsByRole))
                    .toArray(CatalogAuthorizationDTO[]::new);
            RoleMembershipDTO[] roleDTOs =
                roleAccumulators.values().stream()
                    .map(
                        role ->
                            buildRoleMembershipDTO(
                                role,
                                usersByRole.getOrDefault(role.name, Collections.emptySet()),
                                groupsByRole.getOrDefault(role.name, Collections.emptySet())))
                    .toArray(RoleMembershipDTO[]::new);
            String[] unassignedRoles =
                Arrays.stream(roleDTOs)
                    .filter(role -> !role.isAssigned())
                    .map(RoleMembershipDTO::getRole)
                    .toArray(String[]::new);

            return Utils.ok(
                new AuthorizationOverviewResponse(catalogDTOs, roleDTOs, unassignedRoles));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleRoleException(OperationType.LIST, "", metalake, e);
    }
  }

  private Role[] loadVisibleRoles(String metalake) {
    Role[] roles = accessControlDispatcher.listRolesWithSecurableObjects(metalake);
    return MetadataAuthzHelper.filterByExpression(
        metalake,
        AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.ROLE,
        roles,
        role -> NameIdentifierUtil.ofRole(metalake, role.name()));
  }

  private Map<String, Set<String>> initializeMembersByRole(Role[] roles) {
    Map<String, Set<String>> membersByRole = new LinkedHashMap<>();
    Arrays.stream(roles).forEach(role -> membersByRole.put(role.name(), new LinkedHashSet<>()));
    return membersByRole;
  }

  private void collectUsersByRole(Map<String, Set<String>> usersByRole, User[] users) {
    Arrays.stream(users)
        .forEach(
            user ->
                safeRoles(user.roles())
                    .forEach(
                        role -> {
                          Set<String> roleUsers = usersByRole.get(role);
                          if (roleUsers != null) {
                            roleUsers.add(user.name());
                          }
                        }));
  }

  private void collectGroupsByRole(Map<String, Set<String>> groupsByRole, Group[] groups) {
    Arrays.stream(groups)
        .forEach(
            group ->
                safeRoles(group.roles())
                    .forEach(
                        role -> {
                          Set<String> roleGroups = groupsByRole.get(role);
                          if (roleGroups != null) {
                            roleGroups.add(group.name());
                          }
                        }));
  }

  private List<String> safeRoles(List<String> roles) {
    return roles == null ? Collections.emptyList() : roles;
  }

  private void collectRoleObject(
      Map<String, CatalogAccumulator> catalogs,
      RoleAccumulator roleAccumulator,
      SecurableObject object) {
    if (object.privileges().isEmpty()) {
      return;
    }

    boolean privileged = hasWriteOrAdminPrivilege(object);
    roleAccumulator.objectCount++;
    roleAccumulator.privilegeCount += object.privileges().size();
    String catalogName = catalogName(object);
    if (catalogName == null) {
      return;
    }

    roleAccumulator.catalogs.add(catalogName);
    CatalogAccumulator catalog = catalogs.computeIfAbsent(catalogName, CatalogAccumulator::new);
    catalog.roles.add(roleAccumulator.name);
    if (privileged) {
      catalog.privilegedRoles.add(roleAccumulator.name);
    }
    catalog
        .objects
        .computeIfAbsent(MetadataObjectKey.of(object), ObjectAccumulator::new)
        .rolePrivileges
        .add(buildRolePrivilege(roleAccumulator, object));
  }

  private RolePrivilegeDTO buildRolePrivilege(RoleAccumulator role, SecurableObject object) {
    return RolePrivilegeDTO.builder()
        .withRole(role.name)
        .withPrivileges(
            object.privileges().stream().map(DTOConverters::toDTO).toArray(PrivilegeDTO[]::new))
        .withCreateTime(role.createTime)
        .withAssignCount(role.assignCount)
        .build();
  }

  private boolean hasWriteOrAdminPrivilege(SecurableObject object) {
    return object.privileges().stream()
        .filter(privilege -> privilege.condition() == Privilege.Condition.ALLOW)
        .map(privilege -> AuthorizationUtils.replaceLegacyPrivilegeName(privilege.name()))
        .filter(WRITE_OR_ADMIN_PRIVILEGES::contains)
        .anyMatch(
            allowedName ->
                object.privileges().stream()
                    .filter(privilege -> privilege.condition() == Privilege.Condition.DENY)
                    .map(
                        privilege ->
                            AuthorizationUtils.replaceLegacyPrivilegeName(privilege.name()))
                    .noneMatch(allowedName::equals));
  }

  private String catalogName(MetadataObject object) {
    switch (object.type()) {
      case CATALOG:
        return object.name();
      case SCHEMA:
      case FILESET:
      case TABLE:
      case VIEW:
      case TOPIC:
      case COLUMN:
      case MODEL:
      case FUNCTION:
        int separator = object.fullName().indexOf('.');
        return separator < 0 ? null : object.fullName().substring(0, separator);
      default:
        return null;
    }
  }

  private CatalogAuthorizationDTO buildCatalogDTO(
      CatalogAccumulator catalog,
      Map<String, Set<String>> usersByRole,
      Map<String, Set<String>> groupsByRole) {
    Set<String> users = new LinkedHashSet<>();
    Set<String> groups = new LinkedHashSet<>();
    catalog.roles.forEach(
        role -> {
          users.addAll(usersByRole.getOrDefault(role, Collections.emptySet()));
          groups.addAll(groupsByRole.getOrDefault(role, Collections.emptySet()));
        });

    List<ObjectAuthorizationDTO> objects = new ArrayList<>(catalog.objects.size());
    for (ObjectAccumulator object : catalog.objects.values()) {
      objects.add(
          new ObjectAuthorizationDTO(
              DTOConverters.toDTO(object.object),
              object.rolePrivileges.toArray(new RolePrivilegeDTO[0])));
    }

    return new CatalogAuthorizationDTO(
        catalog.name,
        objects.toArray(new ObjectAuthorizationDTO[0]),
        catalog.roles.toArray(new String[0]),
        users.toArray(new String[0]),
        groups.toArray(new String[0]),
        privilegedPrincipalCount(catalog, usersByRole, groupsByRole));
  }

  private int privilegedPrincipalCount(
      CatalogAccumulator catalog,
      Map<String, Set<String>> usersByRole,
      Map<String, Set<String>> groupsByRole) {
    Set<String> privilegedUsers = new LinkedHashSet<>();
    Set<String> privilegedGroups = new LinkedHashSet<>();
    catalog.roles.stream()
        .filter(catalog.privilegedRoles::contains)
        .forEach(
            role -> {
              privilegedUsers.addAll(usersByRole.getOrDefault(role, Collections.emptySet()));
              privilegedGroups.addAll(groupsByRole.getOrDefault(role, Collections.emptySet()));
            });
    return privilegedUsers.size() + privilegedGroups.size();
  }

  private RoleMembershipDTO buildRoleMembershipDTO(
      RoleAccumulator role, Set<String> users, Set<String> groups) {
    return new RoleMembershipDTO(
        role.name,
        users.toArray(new String[0]),
        groups.toArray(new String[0]),
        role.catalogs.toArray(new String[0]),
        role.objectCount,
        role.privilegeCount);
  }

  private static class CatalogAccumulator {
    private final String name;
    private final Map<MetadataObjectKey, ObjectAccumulator> objects = new LinkedHashMap<>();
    private final Set<String> roles = new LinkedHashSet<>();
    private final Set<String> privilegedRoles = new LinkedHashSet<>();

    private CatalogAccumulator(String name) {
      this.name = name;
    }
  }

  private static class ObjectAccumulator {
    private final MetadataObjectKey object;
    private final List<RolePrivilegeDTO> rolePrivileges = new ArrayList<>();

    private ObjectAccumulator(MetadataObjectKey object) {
      this.object = object;
    }
  }

  private static class RoleAccumulator {
    private final String name;
    private final Instant createTime;
    private final int assignCount;
    private final Set<String> catalogs = new LinkedHashSet<>();
    private int objectCount;
    private int privilegeCount;

    private RoleAccumulator(String name, Instant createTime, int assignCount) {
      this.name = name;
      this.createTime = createTime;
      this.assignCount = assignCount;
    }
  }

  private static class MetadataObjectKey implements MetadataObject {
    private final String parent;
    private final String name;
    private final Type type;

    private MetadataObjectKey(String parent, String name, Type type) {
      this.parent = parent;
      this.name = name;
      this.type = type;
    }

    private static MetadataObjectKey of(MetadataObject object) {
      return new MetadataObjectKey(object.parent(), object.name(), object.type());
    }

    @Override
    public String parent() {
      return parent;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Type type() {
      return type;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof MetadataObject)) {
        return false;
      }
      MetadataObject that = (MetadataObject) other;
      return type == that.type() && Objects.equals(fullName(), that.fullName());
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, fullName());
    }
  }
}
