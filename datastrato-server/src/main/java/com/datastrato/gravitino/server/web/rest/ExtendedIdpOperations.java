/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.requests.IdpMembershipAddRequest;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Instance-scoped Local IdP REST APIs.
 *
 * <p>Lists active built-in IdP user and group names and bulk-adds {@code idp_user_group_rel}
 * memberships. Metalake-scoped security user and group APIs live on {@link ExtendedUserOperations}
 * and {@link ExtendedGroupOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security")
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
   * Lists Local IdP usernames from {@code idp_user_meta} (instance-scoped, names only).
   *
   * @return Active Local IdP usernames.
   */
  @GET
  @Path("idp/users")
  @Produces("application/vnd.gravitino.v1+json")
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
   * Lists Local IdP group names from {@code idp_group_meta} (instance-scoped, names only).
   *
   * @return Active Local IdP group names.
   */
  @GET
  @Path("idp/groups")
  @Produces("application/vnd.gravitino.v1+json")
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
