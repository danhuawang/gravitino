/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.authorization.IdpNameStatusDTO;
import com.datastrato.gravitino.dto.requests.LocalUserAddRequest;
import com.datastrato.gravitino.dto.requests.UserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserResponse;
import com.datastrato.gravitino.dto.responses.IdpUserNameListResponse;
import com.google.common.collect.Lists;
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
 * Enterprise REST APIs for metalake user administration.
 *
 * <p>Follows the same thin style as {@link ExtendedRoleOperations}: list via dispatcher {@code
 * listExtendedUsers} and {@link ExtendedUserListResponse} (OSS user fields plus {@code origin});
 * add user delegates to {@link DatastratoAccessControlDispatcher#addLocalUser}; batch enabled uses
 * enterprise MetaService SQL behind the dispatcher.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}/users")
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
   * Lists users under a metalake for the security UI, including {@code origin} ({@code Local} vs
   * {@code Provisioned}) from a JOIN to {@code idp_user_meta}.
   *
   * @param metalake The metalake name.
   * @return Users.
   */
  @GET
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
                new ExtendedUserListResponse(accessControlDispatcher.listExtendedUsers(metalake)));
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
  @Path("idp")
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
   * Gets a metalake user for the security UI, including {@code origin}.
   *
   * @param metalake The metalake name.
   * @param user The username.
   * @return The metalake user with {@code origin}.
   */
  @GET
  @Path("{user}")
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
   * <p>Local users ({@code externalId} blank) resolve membership from the built-in IdP. Provisioned
   * users resolve membership from SCIM.
   *
   * @param metalake The metalake name.
   * @param user The username.
   * @return Groups with {@code origin}.
   */
  @GET
  @Path("{user}/groups")
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
  @Path("enabled")
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
