/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.datastrato.gravitino.dto.authorization.DirectoryUserDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.requests.DirectoryUserAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserDeleteRequest;
import com.datastrato.gravitino.dto.requests.DirectoryUserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.DirectoryGroupListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserDetailResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserResponse;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Enterprise REST APIs for Directory user administration.
 *
 * <p>Configure → Directory → Users uses instance-scoped {@code directory/users}. Metalake security
 * Users APIs live on {@link ExtendedMetalakeUserOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path(InternalApiPaths.PREFIX + "/security")
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
   * Lists Directory Users for Configure → Directory → Users (Local / Provisioned).
   *
   * @return Directory users with groups, metalakes, enabled, and origin.
   */
  @GET
  @Path("directory/users")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-list-directory-users." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-directory-users", absolute = true)
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
   * Gets one Directory User for Configure → Directory → User Overview.
   *
   * <p>Instance-scoped (no metalake path): allow {@code SERVICE_ADMIN} or the signed-in user
   * reading their own name. Self-check is done in-method so we do not rely on {@code USER::SELF}
   * building a metalake-scoped NameIdentifier.
   *
   * @param user Username.
   * @param origin Local, Provisioned, or omitted for a metalake-only user.
   * @return User detail including optional {@code externalId}.
   */
  @GET
  @Path("directory/users/{user}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "internal-get-directory-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "internal-get-directory-user", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response getDirectoryUser(
      @PathParam("user") String user, @QueryParam("origin") String origin) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            requireServiceAdminOrSelf(user);
            IdentitySource identitySource = optionalDirectoryOrigin(origin);
            return Utils.ok(
                new DirectoryUserDetailResponse(
                    accessControlDispatcher.getDirectoryUser(user, identitySource)));
          });
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.GET, user, "", e);
    }
  }

  /**
   * Lists Directory Groups for Configure → Directory → User → Groups.
   *
   * <p>Same auth as {@link #getDirectoryUser}: service admin or self.
   *
   * @param user Username.
   * @param origin Local, Provisioned, or omitted for a metalake-only user.
   * @return Groups with {@code memberCount} and {@code origin}.
   */
  @GET
  @Path("directory/users/{user}/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "internal-list-directory-groups-for-user." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-list-directory-groups-for-user", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listDirectoryGroupsForUser(
      @PathParam("user") String user, @QueryParam("origin") String origin) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            requireServiceAdminOrSelf(user);
            IdentitySource identitySource = optionalDirectoryOrigin(origin);
            return Utils.ok(
                new DirectoryGroupListResponse(
                    DirectoryGroupDTO.from(
                        accessControlDispatcher.listDirectoryGroupsForUser(user, identitySource))));
          });
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.LIST, user, "", e);
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
  @Timed(name = "internal-add-directory-user." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "internal-add-directory-user", absolute = true)
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
  @Timed(
      name = "internal-delete-directory-users." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-delete-directory-users", absolute = true)
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
  @Timed(
      name = "internal-batch-update-directory-user-enabled." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "internal-batch-update-directory-user-enabled", absolute = true)
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

  private static IdentitySource optionalDirectoryOrigin(String origin) {
    if (StringUtils.isBlank(origin)) {
      return null;
    }
    IdentitySource identitySource = IdentitySource.fromValue(origin);
    if (identitySource != IdentitySource.LOCAL
        && identitySource != IdentitySource.PROVISIONED
        && identitySource != IdentitySource.JIT) {
      throw new IllegalArgumentException(
          "Query parameter 'origin' must be Local, Provisioned, or JIT, got: " + origin);
    }
    return identitySource;
  }

  /**
   * Allows service admins or the signed-in user matching {@code user}. Used for Directory reads
   * that have no metalake path parameter.
   *
   * @param user Directory username from the path.
   */
  static void requireServiceAdminOrSelf(String user) {
    GravitinoAuthorizer authorizer =
        GravitinoAuthorizerProvider.getInstance().getGravitinoAuthorizer();
    if (authorizer.isServiceAdmin()) {
      return;
    }
    if (Objects.equals(user, PrincipalUtils.getCurrentUserName())) {
      return;
    }
    throw new ForbiddenException(
        "User '%s' is not authorized to access directory user '%s'",
        PrincipalUtils.getCurrentUserName(), user);
  }
}
