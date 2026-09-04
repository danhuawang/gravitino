/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.DirectoryUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.DirectoryUserAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserDeleteRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.DirectoryUserListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserResponse;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Enterprise REST APIs for Directory user administration.
 *
 * <p>Configure → Directory → Users uses instance-scoped {@code directory/users}. Metalake security
 * Users APIs live on {@link ExtendedMetalakeUserOperations}.
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
}
