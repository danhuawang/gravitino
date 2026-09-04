/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.IdpNameStatusDTO;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDTO;
import com.datastrato.gravitino.dto.requests.LocalGroupAddRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.IdpGroupNameListResponse;
import com.datastrato.gravitino.dto.responses.RoleAssignmentListResponse;
import java.util.Arrays;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
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

/**
 * Metalake-scoped security Groups APIs.
 *
 * <p>Class path matches OSS {@code GroupOperations}: {@code .../metalakes/{metalake}/groups}.
 * External URLs are unchanged. A more specific class path wins over a broader metalake-root
 * resource (for example principal-role listings). Instance-scoped Directory / IdP APIs stay on
 * {@link ExtendedGroupOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}/groups")
public class ExtendedMetalakeGroupOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code GroupOperations}).
   */
  public ExtendedMetalakeGroupOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Lists groups under a metalake for the security UI, including {@code origin} and {@code
   * userCount}.
   *
   * @param metalake The metalake name.
   * @return Groups.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response listGroups(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new ExtendedGroupListResponse(
                    accessControlDispatcher.listExtendedGroups(metalake)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, "", metalake, e);
    }
  }

  /**
   * Gets a metalake group for the security Overview page ({@code origin} + {@code userCount} in one
   * SQL).
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The metalake group with {@code origin} and {@code userCount}.
   */
  @GET
  @Path("{group}")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response getGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") @AuthorizationMetadata(type = Entity.EntityType.GROUP) String group) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new ExtendedGroupResponse(
                    accessControlDispatcher.getExtendedGroup(metalake, group)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.GET, group, metalake, e);
    }
  }

  /**
   * Lists built-in IdP groups and whether each is already added to the metalake.
   *
   * @param metalake The metalake name.
   * @return IdP group names with {@code status}.
   */
  @GET
  @Path("idp")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response listIdpGroups(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new IdpGroupNameListResponse(
                    IdpNameStatusDTO.from(accessControlDispatcher.listIdpGroups(metalake))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, "", metalake, e);
    }
  }

  /**
   * Adds an existing local IdP group into a metalake.
   *
   * @param metalake The metalake name.
   * @param request Group name and optional roles.
   * @return The metalake group with {@code origin}.
   */
  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response addGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      LocalGroupAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            request.validate();
            return Utils.ok(
                new ExtendedGroupResponse(
                    ExtendedGroupDTO.from(
                        accessControlDispatcher.addLocalGroup(
                            metalake, request.getName(), request.getRoles()),
                        true,
                        0)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(
          OperationType.ADD, request == null ? "" : request.getName(), metalake, e);
    }
  }

  /**
   * Lists metalake users that belong to the group.
   *
   * <p>Membership is resolved from IdP when the group is in {@code idp_group_meta} (and not SCIM),
   * otherwise from SCIM when the group is in {@code scim_group_meta}. Each user includes {@code
   * origin} and identity-store {@code enabled}.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return Users with {@code origin}.
   */
  @GET
  @Path("{group}/users")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS")
  public Response listUsersForGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") @AuthorizationMetadata(type = Entity.EntityType.GROUP) String group) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new ExtendedUserListResponse(
                    accessControlDispatcher.listExtendedUsersForGroup(metalake, group)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, group, metalake, e);
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
  @Path("{group}/roles")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GROUPS || GROUP::SELF")
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
                    toRoleAssignmentDTOs(
                        accessControlDispatcher.listRoleAssignmentsByGroup(metalake, group))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.GET, group, metalake, e);
    }
  }

  private RoleAssignmentDTO[] toRoleAssignmentDTOs(RoleAssignment[] assignments) {
    return Arrays.stream(assignments)
        .map(
            assignment ->
                new RoleAssignmentDTO(
                    DTOConverters.toDTO(assignment.role()),
                    DTOConverters.toDTO(assignment.assignmentAudit())))
        .toArray(RoleAssignmentDTO[]::new);
  }
}
