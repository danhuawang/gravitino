/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.IdpNameStatusDTO;
import com.datastrato.gravitino.dto.requests.LocalGroupAddRequest;
import com.datastrato.gravitino.dto.responses.DirectoryGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import com.datastrato.gravitino.dto.responses.ExtendedGroupResponse;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.datastrato.gravitino.dto.responses.IdpGroupNameListResponse;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.metalake.MetalakeManager;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Enterprise REST APIs for group administration.
 *
 * <p>Metalake security Groups APIs live under {@code metalakes/{metalake}/groups}. Configure →
 * Directory → Groups uses instance-scoped {@code directory/groups}.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security")
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
   * Lists Directory Groups for Configure → Directory → Groups (Local / Provisioned / JIT).
   *
   * @return Directory groups with memberCount, metalakes, and origin.
   */
  @GET
  @Path("directory/groups")
  @Produces("application/vnd.gravitino.v1+json")
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
   * Lists groups under a metalake for the security UI, including {@code origin} ({@code Local} vs
   * {@code Provisioned}) from a JOIN to {@code idp_group_meta}, and {@code userCount} for the
   * Groups table.
   *
   * @param metalake The metalake name.
   * @return Groups.
   */
  @GET
  @Path("metalakes/{metalake}/groups")
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
   * Lists built-in IdP groups and whether each is already added to the metalake.
   *
   * @param metalake The metalake name.
   * @return IdP group names with {@code status}.
   */
  @GET
  @Path("metalakes/{metalake}/groups/idp")
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
  @Path("metalakes/{metalake}/groups")
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
   * <p>Local groups ({@code externalId} blank) resolve membership from the built-in IdP.
   * Provisioned groups resolve membership from SCIM.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return Users with {@code origin}.
   */
  @GET
  @Path("metalakes/{metalake}/groups/{group}/users")
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
}
