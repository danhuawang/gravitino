/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.rest;

import static org.apache.gravitino.MetadataObject.Type.METALAKE;

import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.TaskStatusDTO;
import com.datastrato.gravitino.search.service.SyncTask;
import com.google.common.collect.ImmutableList;
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
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.server.web.Utils;
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
            // Use sync method to rebuild metadata
            SyncTask task =
                ExtendedDatastratoGravitinoEnv.getInstance()
                    .getSearchService()
                    .rebuildMetadata(metalake);
            return Utils.ok(new SyncMetadataResponse(task.getTaskId()));
          });
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

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            List<SearchEntitiesDTO> result =
                ExtendedDatastratoGravitinoEnv.getInstance()
                    .getSearchService()
                    .query(metalake, keyword, pageNumber, pageSize);
            return Utils.ok(new SearchQueryResponse(result));
          });

    } catch (Exception e) {
      LOG.warn("Failed to query the data", e);
      return Utils.ok(new SearchQueryResponse(ImmutableList.of()));
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
            return Utils.ok(new TaskStatusResponse(taskStatus));
          });

    } catch (Exception e) {
      LOG.error("Failed to get task status for taskId: {}", taskId, e);
      return Utils.internalError(e.getMessage());
    }
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
