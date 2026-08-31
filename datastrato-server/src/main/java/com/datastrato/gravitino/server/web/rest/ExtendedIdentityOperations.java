/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.authorization.GroupLookupInfo;
import com.datastrato.gravitino.dto.authorization.IdentityLookupValidator;
import com.datastrato.gravitino.dto.authorization.IdentityType;
import com.datastrato.gravitino.dto.responses.GroupInfoResponse;
import com.datastrato.gravitino.dto.responses.UserGroupNamesResponse;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;

/**
 * Global identity lookups for the security Add User / Add Group flows.
 *
 * <p>These endpoints intentionally omit {@code metalake} because they read global IdP / SCIM
 * metadata before writing metalake {@code user_meta} / {@code group_meta}.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security/identity")
public class ExtendedIdentityOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /** Creates the resource. */
  public ExtendedIdentityOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Looks up group names for a user before adding the user into a metalake.
   *
   * @param username The username to look up.
   * @param type The identity type ({@code local} or {@code provisioned}).
   * @return Group names for the user.
   */
  @GET
  @Path("users/{username}/groups")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response getUserGroups(
      @PathParam("username") String username, @QueryParam("type") String type) {
    try {
      IdentityType identityType = IdentityType.fromValue(type);
      IdentityLookupValidator.validateUserGroupsLookup(username, identityType);
      return Utils.doAs(
          httpRequest,
          () ->
              Utils.ok(
                  new UserGroupNamesResponse(
                      username,
                      identityType,
                      accessControlDispatcher
                          .lookupUserGroupNames(username, identityType)
                          .toArray(new String[0]))));
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.GET, username, "", e);
    }
  }

  /**
   * Looks up group metadata before adding the group into a metalake.
   *
   * @param groupName The group name to look up.
   * @param type The identity type ({@code local} or {@code provisioned}).
   * @return Group metadata.
   */
  @GET
  @Path("groups/{groupName}")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "SERVICE_ADMIN")
  public Response getGroupInfo(
      @PathParam("groupName") String groupName, @QueryParam("type") String type) {
    try {
      IdentityType identityType = IdentityType.fromValue(type);
      IdentityLookupValidator.validateGroupLookup(groupName, identityType);
      return Utils.doAs(
          httpRequest,
          () -> {
            GroupLookupInfo groupLookupInfo =
                accessControlDispatcher.lookupGroupInfo(groupName, identityType);
            return Utils.ok(
                new GroupInfoResponse(
                    groupLookupInfo.groupName(),
                    identityType,
                    groupLookupInfo.comment(),
                    groupLookupInfo.members()));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.GET, groupName, "", e);
    }
  }
}
