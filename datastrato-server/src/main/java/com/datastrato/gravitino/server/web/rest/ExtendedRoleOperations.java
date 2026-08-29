/*
 * Copyright 2025 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.RoleGroupAssignmentDTO;
import com.datastrato.gravitino.dto.authorization.RoleUserAssignmentDTO;
import com.datastrato.gravitino.dto.requests.PermissionUpdateRequest;
import com.datastrato.gravitino.dto.responses.RoleGroupAssignmentListResponse;
import com.datastrato.gravitino.dto.responses.RoleUserAssignmentListResponse;
import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.dto.authorization.PrivilegeDTO;
import org.apache.gravitino.dto.authorization.SecurableObjectDTO;
import org.apache.gravitino.dto.responses.RoleResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NameBindings.AccessControlInterfaces
@Path("/web/security/metalakes/{metalake}/roles/{role}/")
public class ExtendedRoleOperations {
  private static final Logger LOG = LoggerFactory.getLogger(ExtendedRoleOperations.class);

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates the resource. Dispatcher comes from {@link ExtendedDatastratoGravitinoEnv} rather than
   * constructor injection; Jersey does not bind this type (same as OSS {@code UserOperations}).
   */
  public ExtendedRoleOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  @GET
  @Path("users")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION)
  public Response listUsersByRole(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("role") @AuthorizationMetadata(type = Entity.EntityType.ROLE) String role) {
    try {
      return Utils.doAs(
          httpRequest,
          () ->
              Utils.ok(
                  new RoleUserAssignmentListResponse(
                      Arrays.stream(
                              accessControlDispatcher.listUserAssignmentsByRole(metalake, role))
                          .map(
                              assignment ->
                                  new RoleUserAssignmentDTO(
                                      assignment.user(),
                                      assignment.assignmentAudit(),
                                      assignment.inBuiltInIdp()))
                          .toArray(RoleUserAssignmentDTO[]::new))));
    } catch (Exception e) {
      return ExceptionHandlers.handleRoleException(OperationType.LIST, role, metalake, e);
    }
  }

  @GET
  @Path("groups")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION)
  public Response listGroupsByRole(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("role") @AuthorizationMetadata(type = Entity.EntityType.ROLE) String role) {
    try {
      return Utils.doAs(
          httpRequest,
          () ->
              Utils.ok(
                  new RoleGroupAssignmentListResponse(
                      Arrays.stream(
                              accessControlDispatcher.listGroupAssignmentsByRole(metalake, role))
                          .map(
                              assignment ->
                                  new RoleGroupAssignmentDTO(
                                      assignment.group(),
                                      assignment.assignmentAudit(),
                                      assignment.userCount()))
                          .toArray(RoleGroupAssignmentDTO[]::new))));
    } catch (Exception e) {
      return ExceptionHandlers.handleRoleException(OperationType.LIST, role, metalake, e);
    }
  }

  @PUT
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "METALAKE::OWNER || METALAKE::MANAGE_GRANTS")
  public Response updateBulkPermissions(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("role") @AuthorizationMetadata(type = Entity.EntityType.ROLE) String role,
      PermissionUpdateRequest request) {
    try {
      LOG.info("Updating privileges for role {} on metalake {}", role, metalake);

      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            // Check update objects
            List<SecurableObject> updatedObjects = Lists.newArrayList();

            for (SecurableObjectDTO dto : request.getUpdates()) {
              for (Privilege privilegeDTO : dto.privileges()) {
                AuthorizationUtils.checkPrivilege((PrivilegeDTO) privilegeDTO, dto, metalake);
              }

              MetadataObjectUtil.checkMetadataObject(metalake, dto);
              AuthorizationUtils.checkDuplicatedNamePrivilege(dto.privileges());

              updatedObjects.add(
                  SecurableObjects.parse(
                      dto.fullName(),
                      dto.type(),
                      dto.privileges().stream()
                          .map(
                              privilege -> DTOConverters.fromPrivilegeDTO((PrivilegeDTO) privilege))
                          .collect(Collectors.toList())));
            }

            return Utils.ok(
                new RoleResponse(
                    DTOConverters.toDTO(
                        accessControlDispatcher.updatePrivilegesForRole(
                            metalake, role, updatedObjects))));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleRolePermissionOperationException(
          OperationType.UPDATE, role, metalake, e);
    }
  }
}
