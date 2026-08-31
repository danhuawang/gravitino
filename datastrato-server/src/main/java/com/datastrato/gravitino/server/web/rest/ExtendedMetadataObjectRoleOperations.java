/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.ObjectRolePrivilegeDTO;
import com.datastrato.gravitino.dto.authorization.RolePrivilegeDTO;
import com.datastrato.gravitino.dto.responses.ObjectRolePrivilegeListResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Group;
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

/** REST operations for listing metadata objects with their associated role privileges. */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}/objects/roles")
public class ExtendedMetadataObjectRoleOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /** Creates a new ExtendedMetadataObjectRoleOperations. */
  public ExtendedMetadataObjectRoleOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Lists metadata objects with their associated role privileges under the metalake.
   *
   * @param metalake The metalake name.
   * @return A response containing metadata objects and their associated role privileges.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "")
  public Response listObjectRolePrivileges(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            Role[] roles = accessControlDispatcher.listRolesWithSecurableObjects(metalake);
            roles =
                MetadataAuthzHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.ROLE,
                    roles,
                    role -> NameIdentifierUtil.ofRole(metalake, role.name()));
            Map<String, Integer> assignmentCounts = initializeAssignmentCounts(roles);
            if (roles.length > 0) {
              collectUserAssignmentCounts(
                  assignmentCounts, accessControlDispatcher.listUsers(metalake));
              collectGroupAssignmentCounts(
                  assignmentCounts, accessControlDispatcher.listGroups(metalake));
            }

            Map<MetadataObjectKey, List<RolePrivilegeDTO>> objectRolePrivileges =
                new LinkedHashMap<>();
            Arrays.stream(roles)
                .forEach(
                    role ->
                        collectObjectRolePrivileges(
                            objectRolePrivileges,
                            role,
                            assignmentCounts.getOrDefault(role.name(), 0)));

            ObjectRolePrivilegeDTO[] objectRolePrivilegeDTOs =
                objectRolePrivileges.entrySet().stream()
                    .map(
                        entry ->
                            ObjectRolePrivilegeDTO.builder()
                                .withMetadataObject(DTOConverters.toDTO(entry.getKey()))
                                .withRolePrivileges(
                                    entry.getValue().toArray(new RolePrivilegeDTO[0]))
                                .build())
                    .toArray(ObjectRolePrivilegeDTO[]::new);

            return Utils.ok(
                ObjectRolePrivilegeListResponse.builder()
                    .withObjectRolePrivileges(objectRolePrivilegeDTOs)
                    .build());
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleRoleException(OperationType.LIST, "", metalake, e);
    }
  }

  private void collectObjectRolePrivileges(
      Map<MetadataObjectKey, List<RolePrivilegeDTO>> objectRolePrivileges,
      Role role,
      int assignCount) {
    role.securableObjects()
        .forEach(
            securableObject -> {
              if (securableObject.privileges().isEmpty()) {
                return;
              }

              objectRolePrivileges
                  .computeIfAbsent(
                      MetadataObjectKey.of(securableObject), ignored -> new ArrayList<>())
                  .add(buildRolePrivilege(role, securableObject, assignCount));
            });
  }

  private RolePrivilegeDTO buildRolePrivilege(
      Role role, SecurableObject securableObject, int assignCount) {
    return RolePrivilegeDTO.builder()
        .withRole(role.name())
        .withPrivileges(
            securableObject.privileges().stream()
                .map(DTOConverters::toDTO)
                .toArray(PrivilegeDTO[]::new))
        .withCreateTime(role.auditInfo().createTime())
        .withAssignCount(assignCount)
        .build();
  }

  private Map<String, Integer> initializeAssignmentCounts(Role[] roles) {
    Map<String, Integer> assignmentCounts = new LinkedHashMap<>();
    Arrays.stream(roles).forEach(role -> assignmentCounts.put(role.name(), 0));
    return assignmentCounts;
  }

  private void collectUserAssignmentCounts(Map<String, Integer> assignmentCounts, User[] users) {
    Arrays.stream(users)
        .forEach(
            user ->
                safeRoles(user.roles())
                    .forEach(role -> incrementAssignmentCount(assignmentCounts, role)));
  }

  private void collectGroupAssignmentCounts(Map<String, Integer> assignmentCounts, Group[] groups) {
    Arrays.stream(groups)
        .forEach(
            group ->
                safeRoles(group.roles())
                    .forEach(role -> incrementAssignmentCount(assignmentCounts, role)));
  }

  private void incrementAssignmentCount(Map<String, Integer> assignmentCounts, String role) {
    assignmentCounts.computeIfPresent(role, (ignored, count) -> count + 1);
  }

  private List<String> safeRoles(List<String> roles) {
    return roles == null ? Collections.emptyList() : roles;
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

    private static MetadataObjectKey of(MetadataObject metadataObject) {
      return new MetadataObjectKey(
          metadataObject.parent(), metadataObject.name(), metadataObject.type());
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
