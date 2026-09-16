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
import com.datastrato.gravitino.dto.requests.DirectoryGroupAddRequest;
import com.datastrato.gravitino.dto.requests.DirectoryGroupDeleteRequest;
import com.datastrato.gravitino.dto.responses.DirectoryGroupDetailResponse;
import com.datastrato.gravitino.dto.responses.DirectoryGroupListResponse;
import com.datastrato.gravitino.dto.responses.DirectoryGroupResponse;
import com.datastrato.gravitino.dto.responses.DirectoryUserListResponse;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Enterprise REST APIs for Directory group administration.
 *
 * <p>Configure → Directory → Groups uses instance-scoped {@code directory/groups}. Metalake
 * security Groups APIs live on {@link ExtendedMetalakeGroupOperations}.
 */
@NameBindings.AccessControlInterfaces
@Path(InternalApiPaths.PREFIX + "/security")
public class ExtendedGroupOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code UserOperations}).
   */
  public ExtendedGroupOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Lists Directory Groups for Configure → Directory → Groups (Local / Provisioned).
   *
   * @return Directory groups with memberCount, metalakes, and origin.
   */
  @GET
  @Path("directory/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-list-directory-groups." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-list-directory-groups", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response listDirectoryGroups() {
    try {
      return Utils.doAs(
          httpRequest,
          () ->
              Utils.ok(
                  new DirectoryGroupListResponse(
                      DirectoryGroupDTO.from(accessControlDispatcher.listDirectoryGroups()))));
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, "", "", e);
    }
  }

  /**
   * Gets one Directory Group for Configure → Directory → Group Overview.
   *
   * @param group Group name.
   * @param origin Local or Provisioned.
   * @return Group detail including {@code memberCount}.
   */
  @GET
  @Path("directory/groups/{group}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-get-directory-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-get-directory-group", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response getDirectoryGroup(
      @PathParam("group") String group, @QueryParam("origin") String origin) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            IdentitySource identitySource = requireDirectoryOrigin(origin);
            return Utils.ok(
                new DirectoryGroupDetailResponse(
                    accessControlDispatcher.getDirectoryGroup(group, identitySource)));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.GET, group, "", e);
    }
  }

  /**
   * Lists Directory Users for Configure → Directory → Group → Members.
   *
   * @param group Group name.
   * @param origin Local or Provisioned.
   * @return Members with {@code enabled} (Status) and {@code origin}.
   */
  @GET
  @Path("directory/groups/{group}/users")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(
      name = "web-list-directory-users-for-group." + MetricNames.HTTP_PROCESS_DURATION,
      absolute = true)
  @ResponseMetered(name = "web-list-directory-users-for-group", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response listDirectoryUsersForGroup(
      @PathParam("group") String group, @QueryParam("origin") String origin) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            IdentitySource identitySource = requireDirectoryOrigin(origin);
            return Utils.ok(
                new DirectoryUserListResponse(
                    DirectoryUserDTO.from(
                        accessControlDispatcher.listDirectoryUsersForGroup(
                            group, identitySource))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, group, "", e);
    }
  }

  /**
   * Creates a Local Directory Group in {@code idp_group_meta} and adds IdP user members.
   *
   * @param request Group name, optional comment, and optional member usernames.
   * @return The created Directory Group.
   */
  @POST
  @Path("directory/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-add-directory-group." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-add-directory-group", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response addDirectoryGroup(DirectoryGroupAddRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            return Utils.ok(
                new DirectoryGroupResponse(
                    DirectoryGroupDTO.from(
                        accessControlDispatcher.addDirectoryGroup(
                            request.getName(), request.getComment(), request.getMembers()))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(
          OperationType.ADD, request == null ? "" : request.getName(), "", e);
    }
  }

  /**
   * Soft-deletes Local Directory Groups via the built-in IdP manager.
   *
   * @param request Groups with name and origin; every origin must be Local.
   * @return Soft-deleted group names.
   */
  @POST
  @Path("directory/groups/delete")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-delete-directory-groups." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-delete-directory-groups", absolute = true)
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response deleteDirectoryGroups(DirectoryGroupDeleteRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            List<String> names = new ArrayList<>(request.getGroups().length);
            List<IdentitySource> origins = new ArrayList<>(request.getGroups().length);
            for (DirectoryGroupDeleteRequest.DirectoryGroupDelete group : request.getGroups()) {
              names.add(group.getName());
              origins.add(group.getOrigin());
            }
            return Utils.ok(
                new NameListResponse(
                    accessControlDispatcher
                        .deleteDirectoryGroups(names, origins)
                        .toArray(new String[0])));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.REMOVE, "", "", e);
    }
  }

  private static IdentitySource requireDirectoryOrigin(String origin) {
    if (origin == null || origin.isBlank()) {
      throw new IllegalArgumentException(
          "Query parameter 'origin' is required (Local, Provisioned, or JIT)");
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
}
