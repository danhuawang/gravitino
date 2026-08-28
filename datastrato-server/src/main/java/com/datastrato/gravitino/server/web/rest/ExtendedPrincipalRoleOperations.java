/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDTO;
import com.datastrato.gravitino.dto.responses.RoleAssignmentListResponse;
import java.util.Arrays;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/** REST operations for listing the roles assigned to one user or group. */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}")
public class ExtendedPrincipalRoleOperations {

  private static final String LOAD_USER_PRIVILEGE =
      "METALAKE::OWNER || METALAKE::MANAGE_USERS || USER::SELF";
  private static final String LOAD_GROUP_PRIVILEGE =
      "METALAKE::OWNER || METALAKE::MANAGE_GROUPS || GROUP::SELF";

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /** Creates principal role operations. */
  public ExtendedPrincipalRoleOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
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
}
