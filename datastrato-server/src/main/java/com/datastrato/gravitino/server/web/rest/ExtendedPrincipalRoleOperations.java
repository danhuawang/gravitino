/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDTO;
import com.datastrato.gravitino.dto.authorization.RoleSummaryDTO;
import com.datastrato.gravitino.dto.requests.RoleAssignmentRequest;
import com.datastrato.gravitino.dto.responses.RoleAssignmentListResponse;
import com.datastrato.gravitino.dto.responses.RoleSummaryListResponse;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** REST operations for listing roles and their principal assignments. */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}")
public class ExtendedPrincipalRoleOperations {

  private static final String LOAD_USER_PRIVILEGE =
      "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF";
  private static final String LOAD_GROUP_PRIVILEGE =
      "METALAKE::OWNER || METALAKE::MANAGE_GROUPS || GROUP::SELF";

  private final DatastratoAccessControlDispatcher accessControlDispatcher;
  private final EntityStore entityStore;

  @Context private HttpServletRequest httpRequest;

  /** Creates role and principal role operations. */
  public ExtendedPrincipalRoleOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
    this.entityStore = GravitinoEnv.getInstance().entityStore();
  }

  /**
   * Lists all visible roles with their owners and direct user and group counts.
   *
   * @param metalake The metalake name.
   * @return The visible role summaries.
   */
  @GET
  @Path("roles")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "")
  public Response listRoleSummaries(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            Role[] roles = loadVisibleRoles(metalake);
            String[] roleNames = Arrays.stream(roles).map(Role::name).toArray(String[]::new);
            Map<String, OwnerDTO> owners = loadRoleOwners(metalake, roleNames);
            Map<String, Set<String>> usersByRole = initializeMembersByRole(roleNames);
            Map<String, Set<String>> groupsByRole = initializeMembersByRole(roleNames);
            collectUsersByRole(usersByRole, accessControlDispatcher.listUsers(metalake));
            collectGroupsByRole(groupsByRole, accessControlDispatcher.listGroups(metalake));

            RoleSummaryDTO[] summaries =
                Arrays.stream(roles)
                    .map(
                        role ->
                            new RoleSummaryDTO(
                                role.name(),
                                owners.get(role.name()),
                                role.auditInfo().createTime(),
                                usersByRole.get(role.name()).size(),
                                groupsByRole.get(role.name()).size()))
                    .toArray(RoleSummaryDTO[]::new);
            return Utils.ok(new RoleSummaryListResponse(summaries));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleRoleException(OperationType.LIST, "", metalake, e);
    }
  }

  /**
   * Assigns multiple roles to multiple users and groups.
   *
   * <p>Roles that do not exist are created without privileges. Users and groups that exist in the
   * built-in IdP but not in the metalake are added to the metalake before assignment.
   *
   * @param metalake The metalake name.
   * @param request The roles, users, and groups to assign.
   * @return A successful base response when all assignments are persisted.
   */
  @PUT
  @Path("roles/assignments")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(
      expression =
          "METALAKE::OWNER || (METALAKE::MANAGE_GRANTS && METALAKE::CREATE_ROLE"
              + " && METALAKE::MANAGE_USERS && METALAKE::MANAGE_GROUPS)")
  public Response assignRolesToPrincipals(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      RoleAssignmentRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Preconditions.checkArgument(request != null, "request cannot be null");
            request.validate();
            accessControlDispatcher.assignRolesToPrincipals(
                metalake, request.getRoles(), request.getUsers(), request.getGroups());
            return Utils.ok(new BaseResponse(0));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleRoleException(OperationType.GRANT, "", metalake, e);
    }
  }

  /**
   * Lists all roles assigned to a user, including role privileges and assignment audit information.
   *
   * @param metalake The metalake name.
   * @param user The user name.
   * @return The user's role assignments.
   */
  @GET
  @Path("users/{user}/roles")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = LOAD_USER_PRIVILEGE)
  public Response listUserRoleAssignments(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") @AuthorizationMetadata(type = Entity.EntityType.USER) String user) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new RoleAssignmentListResponse(
                    toDTOs(accessControlDispatcher.listRoleAssignmentsByUser(metalake, user))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.GET, user, metalake, e);
    }
  }

  /**
   * Lists all roles assigned to a group, including role privileges and assignment audit
   * information.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The group's role assignments.
   */
  @GET
  @Path("groups/{group}/roles")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = LOAD_GROUP_PRIVILEGE)
  public Response listGroupRoleAssignments(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") @AuthorizationMetadata(type = Entity.EntityType.GROUP) String group) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new RoleAssignmentListResponse(
                    toDTOs(accessControlDispatcher.listRoleAssignmentsByGroup(metalake, group))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.GET, group, metalake, e);
    }
  }

  private RoleAssignmentDTO[] toDTOs(RoleAssignment[] assignments) {
    return Arrays.stream(assignments)
        .map(
            assignment ->
                new RoleAssignmentDTO(
                    DTOConverters.toDTO(assignment.role()),
                    DTOConverters.toDTO(assignment.assignmentAudit())))
        .toArray(RoleAssignmentDTO[]::new);
  }

  private Role[] loadVisibleRoles(String metalake) {
    Role[] roles = accessControlDispatcher.listRolesWithSecurableObjects(metalake);
    return MetadataListingHelper.filterByExpression(
        metalake,
        AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.ROLE,
        roles,
        role -> NameIdentifierUtil.ofRole(metalake, role.name()));
  }

  private Map<String, Set<String>> initializeMembersByRole(String[] roles) {
    Map<String, Set<String>> membersByRole = new LinkedHashMap<>();
    Arrays.stream(roles).forEach(role -> membersByRole.put(role, new LinkedHashSet<>()));
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

  private Map<String, OwnerDTO> loadRoleOwners(String metalake, String[] roles) throws IOException {
    if (roles.length == 0) {
      return new LinkedHashMap<>();
    }

    List<NameIdentifier> roleIdentifiers = new ArrayList<>(roles.length);
    Arrays.stream(roles)
        .map(role -> NameIdentifierUtil.ofRole(metalake, role))
        .forEach(roleIdentifiers::add);
    List<RelationalEntity<?>> relations =
        entityStore
            .relationOperations()
            .batchListEntitiesByRelation(
                SupportsRelationOperations.Type.OWNER_REL, roleIdentifiers, Entity.EntityType.ROLE);

    Map<String, OwnerDTO> owners = new LinkedHashMap<>();
    relations.forEach(
        relation -> {
          OwnerDTO owner = toOwnerDTO(relation.targetEntity());
          if (owner != null) {
            owners.put(relation.source().name(), owner);
          }
        });
    return owners;
  }

  private OwnerDTO toOwnerDTO(Entity ownerEntity) {
    if (ownerEntity instanceof User) {
      return OwnerDTO.builder()
          .withName(((User) ownerEntity).name())
          .withType(Owner.Type.USER)
          .build();
    }
    if (ownerEntity instanceof Group) {
      return OwnerDTO.builder()
          .withName(((Group) ownerEntity).name())
          .withType(Owner.Type.GROUP)
          .build();
    }
    return null;
  }
}
