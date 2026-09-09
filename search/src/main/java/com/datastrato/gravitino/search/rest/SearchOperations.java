/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.rest;

import static org.apache.gravitino.MetadataObject.Type.METALAKE;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.service.SearchService;
import com.datastrato.gravitino.search.service.SyncTask;
import java.util.List;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.listener.api.event.server.AuthorizationDenialFailureEvent;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/search")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SearchOperations {
  private static final Logger LOG = LoggerFactory.getLogger(SearchOperations.class);

  @Context private HttpServletRequest httpRequest;

  @POST
  @Path("/rebuild/metalakes/{metalake}")
  @Produces("application/vnd.gravitino.v1+json")
  public Response rebuildMetadata(@PathParam("metalake") String metalake) {
    if (StringUtils.isBlank(metalake)) {
      return Utils.illegalArguments("Metalake cannot be null or empty");
    }

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            checkMaintenancePermission(metalake, "rebuildMetadata");
            // Use sync method to rebuild metadata
            SyncTask task =
                ExtendedDatastratoGravitinoEnv.getInstance()
                    .getSearchService()
                    .rebuildMetadata(metalake);
            return Utils.ok(new SyncMetadataResponse(task.getTaskId()));
          });
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (Exception e) {
      LOG.error("Failed to rebuild metadata for metalake: {}", metalake, e);
      return Utils.internalError(e.getMessage());
    }
  }

  @POST
  @Path("/sync/metalakes/{metalake}/objects")
  @Produces("application/vnd.gravitino.v1+json")
  public Response syncMetadataObjects(
      @PathParam("metalake") String metalake, SynMetadataRequest request) {
    if (StringUtils.isBlank(metalake)) {
      return Utils.illegalArguments("Metalake cannot be null or empty");
    }

    MetadataObject metadataObject;
    try {
      request.validate();
      metadataObject = getMetadataObject(request.getMetadataType(), request.getMetadataFullName());
    } catch (Exception e) {
      return Utils.illegalArguments(e.getMessage());
    }

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            checkMaintenancePermission(metalake, "syncMetadataObjects");
            boolean cascade = request.isCascade();
            // If metadataObject is null, it means we are syncing all objects in the metalake; then
            // cascade is always true
            MetadataObject metadataObj = metadataObject;
            if (metadataObj == null) {
              cascade = true;
              metadataObj = MetadataObjects.parse(metalake, METALAKE);
            }

            SyncTask task =
                ExtendedDatastratoGravitinoEnv.getInstance()
                    .getSearchService()
                    .synchronizeMetadata(metalake, metadataObj, cascade);

            return Utils.ok(new SyncMetadataResponse(task.getTaskId()));
          });
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (Exception e) {
      return Utils.internalError(e.getMessage());
    }
  }

  @GET
  @Path("/query")
  @Produces("application/vnd.gravitino.v1+json")
  public Response query(
      @QueryParam("metalake") String metalake,
      @QueryParam("keyword") String keyword,
      @DefaultValue("0") @QueryParam("pageNumber") int pageNumber,
      @DefaultValue("10") @QueryParam("pageSize") int pageSize) {

    if (StringUtils.isBlank(metalake)) {
      return Utils.illegalArguments("Metalake cannot be null or empty");
    }

    if (pageNumber < 0) {
      return Utils.illegalArguments(
          String.format("pageNumber must be greater than or equal to 0: %s", pageNumber));
    }
    if (pageSize <= 0) {
      return Utils.illegalArguments(String.format("pageSize must be greater than 0: %s", pageSize));
    }

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            checkQueryMembership(metalake);

            SearchService searchService =
                ExtendedDatastratoGravitinoEnv.getInstance().getSearchService();
            List<SearchEntitiesDTO> result =
                authorizationEnabled()
                    ? searchService.queryVisible(metalake, keyword, pageNumber, pageSize)
                    : searchService.query(metalake, keyword, pageNumber, pageSize);
            return Utils.ok(new SearchQueryResponse(result));
          });

    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (IllegalArgumentException e) {
      LOG.warn("Invalid search query for metalake: {}", metalake, e);
      return Utils.illegalArguments(e.getMessage(), e);
    } catch (NotFoundException e) {
      LOG.warn("Failed to find metadata for search query in metalake: {}", metalake, e);
      return Utils.notFound(e.getMessage(), e);
    } catch (Exception e) {
      LOG.error("Failed to query data in metalake: {}", metalake, e);
      return Utils.internalError(e.getMessage());
    }
  }

  @GET
  @Path("/task/{taskId}/status")
  @Produces("application/vnd.gravitino.v1+json")
  public Response getTaskStatus(@PathParam("taskId") String taskId) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            TaskStatusDTO taskStatus =
                ExtendedDatastratoGravitinoEnv.getInstance()
                    .getSearchService()
                    .getTaskStatus(taskId);
            if (taskStatus == null) {
              return Utils.illegalArguments("Task not found: " + taskId);
            }
            // The status carries the metalake and the metadata object of the maintenance task, so
            // reading it requires the same permission as submitting one.
            checkMaintenancePermission(taskStatus.getMetalake(), "getTaskStatus");
            return Utils.ok(new TaskStatusResponse(taskStatus));
          });

    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    } catch (Exception e) {
      LOG.error("Failed to get task status for taskId: {}", taskId, e);
      return Utils.internalError(e.getMessage());
    }
  }

  private static boolean authorizationEnabled() {
    return Boolean.TRUE.equals(
        GravitinoEnv.getInstance().config().get(Configs.ENABLE_AUTHORIZATION));
  }

  private void checkQueryMembership(String metalake) {
    if (!authorizationEnabled()) {
      return;
    }

    GravitinoAuthorizer authorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    if (!authorizer.isMetalakeUser(metalake, new AuthorizationRequestContext())) {
      throw authorizationDenied(
          metalake,
          "query",
          "",
          String.format(
              "Current user %s is not a member of the metalake %s, the user must be added to the metalake first",
              PrincipalUtils.getCurrentUserName(), metalake));
    }
  }

  private void checkMaintenancePermission(String metalake, String operation) {
    if (!authorizationEnabled()) {
      return;
    }
    GravitinoAuthorizer authorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    if (!authorizer.isServiceAdmin()
        && !authorizer.isOwner(
            PrincipalUtils.getCurrentPrincipal(),
            metalake,
            MetadataObjects.parse(metalake, METALAKE),
            new AuthorizationRequestContext())) {
      throw authorizationDenied(
          metalake,
          operation,
          "SERVICE_ADMIN || METALAKE::OWNER",
          "Only service administrators or the target metalake owner may rebuild or synchronize search metadata");
    }
  }

  private ForbiddenException authorizationDenied(
      String metalake, String operation, String expression, String message) {
    String user = PrincipalUtils.getCurrentUserName();
    try {
      GravitinoEnv.getInstance()
          .eventBus()
          .dispatchEvent(
              new AuthorizationDenialFailureEvent(
                  user, NameIdentifier.of(metalake), operation, expression));
    } catch (Exception e) {
      LOG.error("Failed to dispatch search authorization denial event for user {}", user, e);
    }
    return new ForbiddenException("%s", message);
  }

  @Nullable
  private MetadataObject getMetadataObject(String metadataType, String metadataFullName) {
    if (StringUtils.isBlank(metadataType) && StringUtils.isBlank(metadataFullName)) {
      return null;
    }

    return MetadataObjects.parse(
        metadataFullName, MetadataObject.Type.valueOf(metadataType.toUpperCase()));
  }
}
