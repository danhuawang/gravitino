/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.requests.IdpMembershipAddRequest;
import com.datastrato.gravitino.dto.requests.IdpPasswordChangeRequest;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Instance-scoped Local IdP REST APIs.
 *
 * <p>Lists active assignable user and group names (identity stores plus metalake-only {@code
 * user_meta} / {@code group_meta}, deduplicated), changes Local IdP passwords (with old-password
 * verification), and bulk-adds {@code idp_user_group_rel} memberships. Metalake-scoped security
 * user and group APIs live on {@link ExtendedUserOperations} and {@link ExtendedGroupOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path(InternalApiPaths.PREFIX + "/security")
public class ExtendedIdpOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code UserOperations}).
   */
  public ExtendedIdpOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Lists distinct usernames from identity stores and metalake-only {@code user_meta} rows
   * (instance-scoped, names only).
   *
   * @return Deduplicated usernames.
   */
  @GET
  @Path("idp/users")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-list-idp-user-names." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-list-idp-user-names", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response listIdpUserNames() {
    try {
      return Utils.doAs(
          httpRequest,
          () -> Utils.ok(new NameListResponse(accessControlDispatcher.listIdpUserNames())));
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.LIST, "", "", e);
    }
  }

  /**
   * Lists distinct group names from identity stores and metalake-only {@code group_meta} rows
   * (instance-scoped, names only).
   *
   * @return Deduplicated group names.
   */
  @GET
  @Path("idp/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-list-idp-group-names." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-list-idp-group-names", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response listIdpGroupNames() {
    try {
      return Utils.doAs(
          httpRequest,
          () -> Utils.ok(new NameListResponse(accessControlDispatcher.listIdpGroupNames())));
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, "", "", e);
    }
  }

  /**
   * Changes a Local IdP user password after verifying the current password.
   *
   * <p>Allowed for service admins or the signed-in user matching {@code username}. Always requires
   * a correct {@code oldPassword} before updating.
   *
   * @param request Username, old password, and new password.
   * @return Empty success response.
   */
  @PUT
  @Path("idp/users/password")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "web-change-idp-user-password." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "web-change-idp-user-password", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response changeIdpUserPassword(IdpPasswordChangeRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            ExtendedUserOperations.requireServiceAdminOrSelf(request.getUsername());
            accessControlDispatcher.changeIdpUserPassword(
                request.getUsername(), request.getOldPassword(), request.getNewPassword());
            return Utils.ok(new BaseResponse());
          });
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (UnauthorizedException e) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(ErrorResponse.unauthorized(e.getClass().getSimpleName(), e.getMessage(), e))
          .type(MediaType.APPLICATION_JSON)
          .build();
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(
          OperationType.UPDATE,
          request == null ? "" : String.valueOf(request.getUsername()),
          "",
          e);
    }
  }

  /**
   * Adds Local IdP users to Local IdP groups (cartesian product of {@code usernames} × {@code
   * groupNames}).
   *
   * <p>Existing active memberships are skipped and do not fail the request.
   *
   * @param request Usernames and group names.
   * @return Empty success response.
   */
  @POST
  @Path("idp/memberships")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-add-idp-memberships." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-add-idp-memberships", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response addIdpUserGroupMemberships(IdpMembershipAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            accessControlDispatcher.addIdpUserGroupMemberships(
                request.getUsernames(), request.getGroupNames());
            return Utils.ok(new BaseResponse());
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(
          OperationType.ADD, request == null ? "" : String.valueOf(request.getUsernames()), "", e);
    }
  }
}
