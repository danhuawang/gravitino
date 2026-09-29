/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDetailsDTO;
import com.datastrato.gravitino.dto.requests.LocalGroupAddRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.RoleAssignmentDetailsListResponse;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/**
 * Metalake-scoped security Groups APIs.
 *
 * <p>Class path matches OSS {@code GroupOperations}: {@code .../metalakes/{metalake}/groups}.
 * External URLs are unchanged. A more specific class path wins over a broader metalake-root
 * resource (for example principal-role listings). Instance-scoped Directory / IdP APIs stay on
 * {@link ExtendedGroupOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path(InternalApiPaths.PREFIX + "/security/metalakes/{metalake}/groups")
public class ExtendedMetalakeGroupOperations {
  private static final String GROUP_MANAGEMENT_AUTHORIZATION_EXPRESSION =
      "METALAKE::OWNER || METALAKE::MANAGE_GROUPS";

  private final DatastratoAccessControlDispatcher accessControlDispatcher;
  private final EntityStore entityStore;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code GroupOperations}).
   */
  public ExtendedMetalakeGroupOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
    this.entityStore = GravitinoEnv.getInstance().entityStore();
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
  @Timed(
      name = "internal-list-metalake-groups." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-metalake-groups", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listGroups(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            ExtendedGroupDTO[] groups = accessControlDispatcher.listExtendedGroups(metalake);
            groups =
                MetadataListingHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.GROUP,
                    groups,
                    group -> NameIdentifierUtil.ofGroup(metalake, group.name()));
            return Utils.ok(new ExtendedGroupListResponse(groups));
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
  @Timed(name = "internal-get-metalake-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "internal-get-metalake-group", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION)
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
   * Adds an existing local IdP group into a metalake.
   *
   * @param metalake The metalake name.
   * @param request Group name and optional roles.
   * @return The metalake group with {@code origin}.
   */
  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "internal-add-metalake-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "internal-add-metalake-group", absolute = true)
  @AuthorizationExpression(expression = GROUP_MANAGEMENT_AUTHORIZATION_EXPRESSION)
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
  @Timed(
      name = "internal-list-users-for-group." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-users-for-group", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION)
  public Response listUsersForGroup(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") @AuthorizationMetadata(type = Entity.EntityType.GROUP) String group) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            ExtendedUserDTO[] users =
                accessControlDispatcher.listExtendedUsersForGroup(metalake, group);
            users =
                MetadataListingHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.USER,
                    users,
                    user -> NameIdentifierUtil.ofUser(metalake, user.name()));
            return Utils.ok(new ExtendedUserListResponse(users));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, group, metalake, e);
    }
  }

  /**
   * Lists all roles assigned to a group with members, owner, and assignment information.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The group's role assignments.
   */
  @GET
  @Path("{group}/roles")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-list-group-role-assignments." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-group-role-assignments", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION)
  public Response listGroupRoleAssignments(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("group") @AuthorizationMetadata(type = Entity.EntityType.GROUP) String group) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            RoleAssignment[] assignments =
                accessControlDispatcher.listRoleAssignmentsByGroup(metalake, group);
            assignments =
                MetadataListingHelper.filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.ROLE,
                    assignments,
                    assignment -> NameIdentifierUtil.ofRole(metalake, assignment.role().name()));
            RoleAssignmentDetailsDTO[] details =
                RoleAssignmentDTOConverter.toDTOs(
                    metalake, assignments, entityStore.relationOperations());
            details = MetadataListingHelper.filterRoleAssignmentDetails(metalake, details);
            return Utils.ok(new RoleAssignmentDetailsListResponse(details));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.GET, group, metalake, e);
    }
  }
}
