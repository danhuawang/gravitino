/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.dto.requests.CatalogWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.SchemaWithTagsCreateRequest;
import com.datastrato.gravitino.dto.responses.CatalogWithTagsResponse;
import com.datastrato.gravitino.dto.responses.SchemaWithTagsResponse;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
import org.apache.gravitino.Schema;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.SchemaDTO;
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
  private final DatastratoSchemaDispatcher schemaDispatcher;

  private final TagManager tagManager;

  @Inject
  public CreationWithTagsOperations(
      CatalogDispatcher catalogDispatcher,
      DatastratoSchemaDispatcher schemaDispatcher,
      TagManager tagManager) {
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = schemaDispatcher;
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
            CatalogDTO catalogDTO = createCatalog(metalake, request);
            catalogCreated.set(true);

            String[] tagNames =
                addTags(
                    metalake,
                    catalogDTO.name(),
                    MetadataObject.Type.CATALOG,
                    request.getTagsToAdd());
            LOG.info(
                "Tags associated with catalog: {}.{}: {}",
                metalake,
                catalogDTO.name(),
                Arrays.toString(tagNames));

            return Utils.ok(new CatalogWithTagsResponse(catalogDTO, tagNames));
          });

    } catch (Exception e) {
      return rollbackIfNecessary(
          catalogCreated.get(),
          request.getName(),
          e,
          () ->
              TreeLockUtils.doWithTreeLock(
                  NameIdentifierUtil.ofMetalake(metalake),
                  LockType.WRITE,
                  () ->
                      catalogDispatcher.dropCatalog(
                          NameIdentifierUtil.ofCatalog(metalake, request.getName()))),
          () ->
              ExceptionHandlers.handleCatalogException(
                  OperationType.CREATE, request.getName(), metalake, e));
    }
  }

  @POST
  @Path("{catalog}/schemas")
  @Produces("application/vnd.gravitino.v1+json")
  public Response createSchemaWithTag(
      @PathParam("metalake") String metalake,
      @PathParam("catalog") String catalog,
      SchemaWithTagsCreateRequest request) {
    LOG.info("Received create schema request: {}.{}.{}", metalake, catalog, request.getName());

    AtomicBoolean schemaCreated = new AtomicBoolean(false);
    String fullName = String.join(".", catalog, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            SchemaDTO schemaDTO = createSchema(metalake, catalog, request);
            schemaCreated.set(true);

            String[] tagNames =
                addTags(metalake, fullName, MetadataObject.Type.SCHEMA, request.getTagsToAdd());
            LOG.info(
                "Tags associated with schema: {}.{}.{}: {}",
                metalake,
                catalog,
                schemaDTO.name(),
                Arrays.toString(tagNames));

            return Utils.ok(new SchemaWithTagsResponse(schemaDTO, tagNames));
          });

    } catch (Exception e) {
      return rollbackIfNecessary(
          schemaCreated.get(),
          fullName,
          e,
          () ->
              TreeLockUtils.doWithTreeLock(
                  NameIdentifierUtil.ofCatalog(metalake, catalog),
                  LockType.WRITE,
                  () ->
                      schemaDispatcher.dropSchema(
                          NameIdentifierUtil.ofSchema(metalake, catalog, request.getName()),
                          false)),
          () ->
              ExceptionHandlers.handleSchemaException(
                  OperationType.CREATE, request.getName(), catalog, e));
    }
  }

  private CatalogDTO createCatalog(String metalake, CatalogWithTagsCreateRequest request) {
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
    return catalogDTO;
  }

  private SchemaDTO createSchema(
      String metalake, String catalog, SchemaWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident = NameIdentifierUtil.ofSchema(metalake, catalog, request.getName());
    Schema schema =
        TreeLockUtils.doWithTreeLock(
            NameIdentifierUtil.ofCatalog(metalake, catalog),
            LockType.WRITE,
            () ->
                schemaDispatcher.createSchema(
                    ident, request.getComment(), request.getProperties()));
    SchemaDTO schemaDTO = DTOConverters.toDTO(schema);
    LOG.info("Schema created: {}.{}.{}", metalake, catalog, schema.name());
    return schemaDTO;
  }

  private String[] addTags(
      String metalake, String fullName, MetadataObject.Type objectType, String[] tags) {
    if (tags == null || tags.length == 0) {
      return new String[0];
    }
    MetadataObject object = MetadataObjects.parse(fullName, objectType);
    return tagManager.associateTagsForMetadataObject(metalake, object, tags, new String[0]);
  }

  private Response rollbackIfNecessary(
      boolean needRollback,
      String objectFullName,
      Exception originalException,
      Runnable rollbackAction,
      Supplier<Response> creationErrorHandler) {
    if (!needRollback) {
      return creationErrorHandler.get();
    }

    try {
      rollbackAction.run();
    } catch (Exception e) {
      LOG.error("Failed to rollback the created object: {}", objectFullName, e);
    }
    // If rollback is required, it indicates that the object creation was successful but the
    // associated tag failed.
    return ExceptionHandlers.handleTagException(
        OperationType.ASSOCIATE, "", objectFullName, originalException);
  }
}
