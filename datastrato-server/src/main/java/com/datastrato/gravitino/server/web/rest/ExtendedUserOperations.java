/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.DirectoryUserDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.authorization.IdpNameStatusDTO;
import com.datastrato.gravitino.dto.requests.DirectoryUserAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserDeleteRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.requests.LocalUserAddRequest;
import com.datastrato.gravitino.dto.requests.UserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.DirectoryUserListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserResponse;
import com.datastrato.gravitino.dto.responses.IdpUserNameListResponse;
import com.google.common.collect.Lists;
import java.util.ArrayList;
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
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Enterprise REST APIs for user administration.
 *
 * <p>Metalake security Users APIs live under {@code metalakes/{metalake}/users}. Configure →
 * Directory → Users uses instance-scoped {@code directory/users}.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security")
public class ExtendedUserOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code UserOperations}).
   */
  public ExtendedUserOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Lists Directory Users for Configure → Directory → Users (Local / Provisioned / JIT).
   *
   * @return Directory users with groups, metalakes, enabled, and origin.
   */
  @GET
  @Path("directory/users")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response listDirectoryUsers() {
    try {
      return Utils.doAs(
          httpRequest,
          () ->
              Utils.ok(
                  new DirectoryUserListResponse(
                      DirectoryUserDTO.from(accessControlDispatcher.listDirectoryUsers()))));
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.LIST, "", "", e);
    }
  }

  /**
   * Creates a Local Directory User in {@code idp_user_meta} and adds the user to IdP groups.
   *
   * @param request Username, password, and optional group names.
   * @return The created Directory User.
   */
  @POST
  @Path("directory/users")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response addDirectoryUser(DirectoryUserAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            return Utils.ok(
                new DirectoryUserResponse(
                    DirectoryUserDTO.from(
                        accessControlDispatcher.addDirectoryUser(
                            request.getName(), request.getPassword(), request.getGroupNames()))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(
          OperationType.ADD, request == null ? "" : request.getName(), "", e);
    }
  }

  /**
   * Soft-deletes Local Directory Users via the built-in IdP manager.
   *
   * @param request Users with name and origin; every origin must be Local.
   * @return Soft-deleted usernames.
   */
  @POST
  @Path("directory/users/delete")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response deleteDirectoryUsers(DirectoryUserDeleteRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            List<String> names = new ArrayList<>(request.getUsers().length);
            List<IdentitySource> origins = new ArrayList<>(request.getUsers().length);
            for (DirectoryUserDeleteRequest.DirectoryUserDelete user : request.getUsers()) {
              names.add(user.getName());
              origins.add(user.getOrigin());
            }
            return Utils.ok(
                new NameListResponse(
                    accessControlDispatcher
                        .deleteDirectoryUsers(names, origins)
                        .toArray(new String[0])));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.REMOVE, "", "", e);
    }
  }

  /**
   * Batch-updates {@code enabled} for Local Directory Users in {@code idp_user_meta}.
   *
   * @param request Users with name and origin, plus target enabled value.
   * @return Updated usernames.
   */
  @PUT
  @Path("directory/users/enabled")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response batchUpdateDirectoryUserEnabled(DirectoryUserEnabledBatchUpdateRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            List<String> names = new ArrayList<>(request.getUsers().length);
            List<IdentitySource> origins = new ArrayList<>(request.getUsers().length);
            for (DirectoryUserEnabledBatchUpdateRequest.DirectoryUserEnabledUpdate user :
                request.getUsers()) {
              names.add(user.getName());
              origins.add(user.getOrigin());
            }
            return Utils.ok(
                new NameListResponse(
                    accessControlDispatcher
                        .batchUpdateDirectoryUserEnabled(names, origins, request.getEnabled())
                        .toArray(new String[0])));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.UPDATE, "", "", e);
    }
  }

  /**
   * Lists users under a metalake for the security UI, including {@code origin} ({@code Local} vs
   * {@code Provisioned}) from a JOIN to {@code idp_user_meta}.
   *
   * @param metalake The metalake name.
   * @return Users.
   */
  @GET
  @Path("metalakes/{metalake}/users")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
  public Response listUsers(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new ExtendedUserListResponse(
                    ExtendedUserDTO.from(accessControlDispatcher.listUsersWithGroups(metalake))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.LIST, "", metalake, e);
    }
  }

  /**
   * Lists built-in IdP users and whether each is already added to the metalake.
   *
   * @param metalake The metalake name.
   * @return IdP usernames with {@code status}.
   */
  @GET
  @Path("metalakes/{metalake}/users/idp")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
  public Response listIdpUsers(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new IdpUserNameListResponse(
                    IdpNameStatusDTO.from(accessControlDispatcher.listIdpUsers(metalake))));
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
  @Path("metalakes/{metalake}/users")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
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
  @Path("metalakes/{metalake}/users/{user}")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
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
  @Path("metalakes/{metalake}/users/{user}/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
  public Response listGroupsForUser(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("user") @AuthorizationMetadata(type = Entity.EntityType.USER) String user) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            MetalakeManager.checkMetalakeInUse(metalake);
            return Utils.ok(
                new ExtendedGroupListResponse(
                    accessControlDispatcher.listExtendedGroupsForUser(metalake, user)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.LIST, user, metalake, e);
    }
  }

  /**
   * Batch-updates {@code enabled} for users under a metalake.
   *
   * @param metalake The metalake name.
   * @param request User names and target enabled value.
   * @return Updated user names.
   */
  @PUT
  @Path("metalakes/{metalake}/users/enabled")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_USERS")
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
            return Utils.ok(
                new NameListResponse(
                    accessControlDispatcher
                        .batchUpdateUserEnabled(
                            metalake, Lists.newArrayList(request.getUsers()), request.getEnabled())
                        .toArray(new String[0])));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.UPDATE, "", metalake, e);
    }
  }
}
