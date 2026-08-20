/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import com.datastrato.gravitino.dto.requests.UserEnabledBatchUpdateRequest;
import com.datastrato.gravitino.dto.responses.ExtendedUserListResponse;
import com.google.common.collect.Lists;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
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
 * listUsers} and {@link ExtendedUserListResponse} (OSS user fields plus {@code origin}); batch
 * enabled uses enterprise MetaService SQL behind the dispatcher.
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
   * {@code Provisioned}) derived from {@code externalId}.
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
                new ExtendedUserListResponse(
                    ExtendedUserDTO.from(accessControlDispatcher.listUsers(metalake))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleUserException(OperationType.LIST, "", metalake, e);
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
