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
import com.datastrato.gravitino.dto.requests.LocalUserAddRequest;
import com.datastrato.gravitino.dto.requests.UserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserResponse;
import com.datastrato.gravitino.dto.responses.RoleAssignmentDetailsListResponse;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import com.google.common.collect.Lists;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.dto.responses.NameListResponse;
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
 * Metalake-scoped security Users APIs.
 *
 * <p>Class path matches OSS {@code UserOperations}: {@code .../metalakes/{metalake}/users}.
 * External URLs are unchanged. A more specific class path wins over a broader metalake-root
 * resource (for example principal-role listings), so {@code GET .../users} is not stolen.
 * Instance-scoped Directory / IdP APIs stay on {@link ExtendedUserOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path(InternalApiPaths.PREFIX + "/security/metalakes/{metalake}/users")
public class ExtendedMetalakeUserOperations {
  private static final String USER_MANAGEMENT_AUTHORIZATION_EXPRESSION =
      "METALAKE::OWNER || METALAKE::MANAGE_USERS";

  private final DatastratoAccessControlDispatcher accessControlDispatcher;
  private final EntityStore entityStore;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code UserOperations}).
   */
  public ExtendedMetalakeUserOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
    this.entityStore = GravitinoEnv.getInstance().entityStore();
  }

  /**
   * Lists users under a metalake for the security UI, including {@code origin} ({@code Local} vs
   * {@code Provisioned}) from a JOIN to {@code idp_user_meta}.
   *
   * @param metalake The metalake name.
   * @return Users.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-list-metalake-users." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-metalake-users", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listUsers(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            ExtendedUserDTO[] users =
                ExtendedUserDTO.from(accessControlDispatcher.listUsersWithGroups(metalake));
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
      return ExceptionHandlers.handleUserException(OperationType.LIST, "", metalake, e);
    }
  }

  /**
   * Adds an existing local IdP user into a metalake.
   *
   * @param metalake The metalake name.
   * @param request Username, optional roles, and optional enabled flag.
   * @return The metalake user with {@code origin}.
   */
  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "internal-add-metalake-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "internal-add-metalake-user", absolute = true)
  @AuthorizationExpression(expression = USER_MANAGEMENT_AUTHORIZATION_EXPRESSION)
  public Response addUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      LocalUserAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            request.validate();
            return Utils.ok(
                new ExtendedUserResponse(
                    ExtendedUserDTO.from(
                        accessControlDispatcher.addLocalUser(
                            metalake, request.getName(), request.getRoles(), request.getEnabled()),
                        true)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(
          OperationType.ADD, request == null ? "" : request.getName(), metalake, e);
    }
  }

  /**
   * Gets a metalake user for the security Overview page (origin + enabled in one SQL).
   *
   * @param metalake The metalake name.
   * @param user The username.
   * @return The metalake user with {@code origin} and identity-store {@code enabled}.
   */
  @GET
  @Path("{user}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "internal-get-metalake-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "internal-get-metalake-user", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION)
  public Response getUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") @AuthorizationMetadata(type = Entity.EntityType.USER) String user) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new ExtendedUserResponse(accessControlDispatcher.getExtendedUser(metalake, user)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.GET, user, metalake, e);
    }
  }

  /**
   * Lists metalake groups the user belongs to.
   *
   * <p>Membership is resolved from IdP when the user is in {@code idp_user_meta} (and not SCIM),
   * otherwise from SCIM when the user is in {@code scim_user_meta}. Each group includes {@code
   * origin} (Local / Provisioned / JIT).
   *
   * @param metalake The metalake name.
   * @param user The username.
   * @return Groups with {@code origin}.
   */
  @GET
  @Path("{user}/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-list-groups-for-user." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-groups-for-user", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION)
  public Response listGroupsForUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") @AuthorizationMetadata(type = Entity.EntityType.USER) String user) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            ExtendedGroupDTO[] groups =
                accessControlDispatcher.listExtendedGroupsForUser(metalake, user);
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
      return ExceptionHandlers.handleUserException(OperationType.LIST, user, metalake, e);
    }
  }

  /**
   * Batch-updates instance-scoped identity-store {@code enabled} status for users selected through
   * a metalake.
   *
   * @param metalake The metalake name.
   * @param request User names and target enabled value.
   * @return Updated user names.
   */
  @PUT
  @Path("enabled")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-batch-update-user-enabled." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-batch-update-user-enabled", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response batchUpdateUserEnabled(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      UserEnabledBatchUpdateRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            request.validate();
            List<String> users = Lists.newArrayList(request.getUsers());
            return Utils.ok(
                new NameListResponse(
                    accessControlDispatcher
                        .batchUpdateUserEnabled(metalake, users, request.getEnabled())
                        .toArray(new String[0])));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.UPDATE, "", metalake, e);
    }
  }

  /**
   * Lists all roles assigned to a user with members, owner, and assignment information.
   *
   * @param metalake The metalake name.
   * @param user The user name.
   * @return The user's role assignments.
   */
  @GET
  @Path("{user}/roles")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-list-user-role-assignments." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-user-role-assignments", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION)
  public Response listUserRoleAssignments(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") @AuthorizationMetadata(type = Entity.EntityType.USER) String user) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            RoleAssignment[] assignments =
                accessControlDispatcher.listRoleAssignmentsByUser(metalake, user);
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
      return ExceptionHandlers.handleUserException(OperationType.GET, user, metalake, e);
    }
  }
}
