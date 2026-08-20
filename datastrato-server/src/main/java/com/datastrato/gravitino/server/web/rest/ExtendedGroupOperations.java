/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.responses.ExtendedGroupListResponse;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
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
 * Enterprise REST APIs for metalake group administration.
 *
 * <p>Follows the same thin style as {@link ExtendedRoleOperations}: call {@link
 * DatastratoAccessControlDispatcher#listGroups(String)} and map to {@link ExtendedGroupDTO} with
 * {@code origin}.
 */
@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}/groups")
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
   * Lists groups under a metalake for the security UI, including {@code origin} ({@code Local} vs
   * {@code Provisioned}) derived from {@code externalId}.
   *
   * @param metalake The metalake name.
   * @return Groups.
   */
  @GET
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
                    ExtendedGroupDTO.from(accessControlDispatcher.listGroups(metalake))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleGroupException(OperationType.LIST, "", metalake, e);
    }
  }
}
