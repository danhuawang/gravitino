/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.dto.requests.CatalogWithTagsCreateRequest;
import com.datastrato.gravitino.dto.responses.CatalogWithTagsResponse;
import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.tag.TagManager;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/web/with-tags/metalakes/{metalake}/catalogs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CreationWithTagsOperations {

  private static final Logger LOG = LoggerFactory.getLogger(CreationWithTagsOperations.class);

  @Context private HttpServletRequest httpRequest;

  private final CatalogDispatcher catalogDispatcher;

  private final TagManager tagManager;

  @Inject
  public CreationWithTagsOperations(CatalogDispatcher catalogDispatcher, TagManager tagManager) {
    this.catalogDispatcher = catalogDispatcher;
    this.tagManager = tagManager;
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  public Response createCatalogWithTag(
      @PathParam("metalake") String metalake, CatalogWithTagsCreateRequest request) {
    LOG.info("Received create catalog request for metalake: {}", metalake);

    AtomicBoolean catalogCreated = new AtomicBoolean(false);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident = NameIdentifierUtil.ofCatalog(metalake, request.getName());
            Catalog catalog =
                TreeLockUtils.doWithTreeLock(
                    NameIdentifierUtil.ofMetalake(metalake),
                    LockType.WRITE,
                    () ->
                        catalogDispatcher.createCatalog(
                            ident,
                            request.getType(),
                            request.getProvider(),
                            request.getComment(),
                            request.getProperties()));
            CatalogDTO catalogDTO = DTOConverters.toDTO(catalog);
            LOG.info("Catalog created: {}.{}", metalake, catalog.name());
            catalogCreated.set(true);

            if (request.getTagsToAdd() == null || request.getTagsToAdd().length == 0) {
              return Utils.ok(new CatalogWithTagsResponse(catalogDTO, new String[0]));
            }

            MetadataObject object =
                MetadataObjects.of(Lists.newArrayList(catalog.name()), MetadataObject.Type.CATALOG);
            String[] tagNames =
                tagManager.associateTagsForMetadataObject(
                    metalake, object, request.getTagsToAdd(), new String[0]);
            LOG.info(
                "Tags associated with catalog: {}.{}: {}",
                metalake,
                catalog.name(),
                Arrays.toString(tagNames));

            return Utils.ok(new CatalogWithTagsResponse(catalogDTO, tagNames));
          });

    } catch (Exception e) {
      if (!catalogCreated.get()) {
        return ExceptionHandlers.handleCatalogException(
            OperationType.CREATE, request.getName(), metalake, e);
      }

      // rollback the created catalog
      try {
        TreeLockUtils.doWithTreeLock(
            NameIdentifierUtil.ofMetalake(metalake),
            LockType.WRITE,
            () ->
                catalogDispatcher.dropCatalog(
                    NameIdentifierUtil.ofCatalog(metalake, request.getName())));
      } catch (Exception e1) {
        LOG.error(
            "Failed to rollback the catalogCreated catalog: {}.{}",
            metalake,
            request.getName(),
            e1);
      }

      return ExceptionHandlers.handleTagException(
          OperationType.ASSOCIATE, "", String.join(".", metalake, request.getName()), e);
    }
  }
}
