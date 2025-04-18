/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.rest;

import com.datastrato.gravitino.search.service.SearchService;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.server.web.Utils;

@Path("/search")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SearchOperations {

  @Context private HttpServletRequest httpRequest;

  @POST
  @Path("/sync/{metalake}/objects")
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
            SearchService.getSearchService()
                .synchronizeMetadata(metalake, metadataObject, request.isCascade());
            return Utils.ok(new BaseResponse());
          });
    } catch (Exception e) {
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
