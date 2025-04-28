/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.dto.util.DTOConverters.fromDTO;
import static org.apache.gravitino.dto.util.DTOConverters.fromDTOs;
import static org.apache.gravitino.file.Fileset.LOCATION_NAME_UNKNOWN;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.dto.requests.CatalogWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.FilesetWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.ModelWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.SchemaWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.TableWithTagsCreateRequest;
import com.datastrato.gravitino.dto.requests.TopicWithTagsCreateRequest;
import com.datastrato.gravitino.dto.responses.CatalogWithTagsResponse;
import com.datastrato.gravitino.dto.responses.FilesetWithTagsResponse;
import com.datastrato.gravitino.dto.responses.ModelWithTagsResponse;
import com.datastrato.gravitino.dto.responses.SchemaWithTagsResponse;
import com.datastrato.gravitino.dto.responses.TableWithTagsResponse;
import com.datastrato.gravitino.dto.responses.TopicWithTagsResponse;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.messaging.Topic;
import org.apache.gravitino.model.Model;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.tag.TagDispatcher;
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
  private final DatastratoTableDispatcher tableDispatcher;
  private final DatastratoFilesetDispatcher filesetDispatcher;
  private final DatastratoTopicDispatcher topicDispatcher;
  private final DatastratoModelDispatcher modelDispatcher;

  private final TagDispatcher tagDispatcher;

  @Inject
  public CreationWithTagsOperations(
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      TagDispatcher tagDispatcher) {
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = (DatastratoSchemaDispatcher) schemaDispatcher;
    this.tableDispatcher = (DatastratoTableDispatcher) tableDispatcher;
    this.filesetDispatcher = (DatastratoFilesetDispatcher) filesetDispatcher;
    this.topicDispatcher = (DatastratoTopicDispatcher) topicDispatcher;
    this.modelDispatcher = (DatastratoModelDispatcher) modelDispatcher;
    this.tagDispatcher = tagDispatcher;
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  public Response createCatalogWithTag(
      @PathParam("metalake") String metalake, CatalogWithTagsCreateRequest request) {
    LOG.info(
        "Received create catalog with tags request: {}.{}: {}",
        metalake,
        request.getName(),
        Arrays.toString(request.getTagsToAdd()));

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
              catalogDispatcher.dropCatalog(
                  NameIdentifierUtil.ofCatalog(metalake, request.getName())),
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
    LOG.info(
        "Received create schema with tags request: {}.{}.{}: {}",
        metalake,
        catalog,
        request.getName(),
        Arrays.toString(request.getTagsToAdd()));

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
              schemaDispatcher.dropSchema(
                  NameIdentifierUtil.ofSchema(metalake, catalog, request.getName()), false),
          () ->
              ExceptionHandlers.handleSchemaException(
                  OperationType.CREATE, request.getName(), catalog, e));
    }
  }

  @POST
  @Path("{catalog}/schemas/{schema}/tables")
  @Produces("application/vnd.gravitino.v1+json")
  public Response createTableWithTag(
      @PathParam("metalake") String metalake,
      @PathParam("catalog") String catalog,
      @PathParam("schema") String schema,
      TableWithTagsCreateRequest request) {
    LOG.info(
        "Received create table with tags request: {}.{}.{}.{}: {}",
        metalake,
        catalog,
        schema,
        request.getName(),
        Arrays.toString(request.getTagsToAdd()));

    AtomicBoolean tableCreated = new AtomicBoolean(false);
    String fullName = String.join(".", catalog, schema, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            TableDTO tableDTO = createTable(metalake, catalog, schema, request);
            tableCreated.set(true);

            String[] tableTags =
                addTags(metalake, fullName, MetadataObject.Type.TABLE, request.getTagsToAdd());
            LOG.info(
                "Tags associated with table: {}.{}.{}.{}: {}",
                metalake,
                catalog,
                schema,
                tableDTO.name(),
                Arrays.toString(tableTags));

            Map<String, String[]> columnTagsResult = new HashMap<>();
            if (request.getColumnTags() != null) {
              Map<String, String[]> columnTags = request.getColumnTags();
              for (String columnName : columnTags.keySet()) {
                String[] tags =
                    addTags(
                        metalake,
                        String.join(".", fullName, columnName),
                        MetadataObject.Type.COLUMN,
                        columnTags.get(columnName));
                LOG.info(
                    "Tags associated with column: {}.{}.{}.{}.{}: {}",
                    metalake,
                    catalog,
                    schema,
                    tableDTO.name(),
                    columnName,
                    Arrays.toString(tags));
                columnTagsResult.put(columnName, tags);
              }
            }
            return Utils.ok(new TableWithTagsResponse(tableDTO, tableTags, columnTagsResult));
          });

    } catch (Exception e) {
      return rollbackIfNecessary(
          tableCreated.get(),
          fullName,
          e,
          () ->
              tableDispatcher.dropTable(
                  NameIdentifierUtil.ofTable(metalake, catalog, schema, request.getName())),
          () ->
              ExceptionHandlers.handleTableException(
                  OperationType.CREATE, request.getName(), schema, e));
    }
  }

  @POST
  @Path("{catalog}/schemas/{schema}/filesets")
  @Produces("application/vnd.gravitino.v1+json")
  public Response createFilesetWithTag(
      @PathParam("metalake") String metalake,
      @PathParam("catalog") String catalog,
      @PathParam("schema") String schema,
      FilesetWithTagsCreateRequest request) {
    LOG.info(
        "Received create fileset with tags request: {}.{}.{}.{}: {}",
        metalake,
        catalog,
        schema,
        request.getName(),
        Arrays.toString(request.getTagsToAdd()));

    AtomicBoolean filesetCreated = new AtomicBoolean(false);
    String fullName = String.join(".", catalog, schema, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            FilesetDTO filesetDTO = createFileset(metalake, catalog, schema, request);
            filesetCreated.set(true);

            String[] filesetTags =
                addTags(metalake, fullName, MetadataObject.Type.FILESET, request.getTagsToAdd());
            LOG.info(
                "Tags associated with fileset: {}.{}.{}.{}: {}",
                metalake,
                catalog,
                schema,
                filesetDTO.name(),
                Arrays.toString(filesetTags));

            return Utils.ok(new FilesetWithTagsResponse(filesetDTO, filesetTags));
          });

    } catch (Exception e) {
      return rollbackIfNecessary(
          filesetCreated.get(),
          fullName,
          e,
          () ->
              filesetDispatcher.dropFileset(
                  NameIdentifierUtil.ofFileset(metalake, catalog, schema, request.getName())),
          () ->
              ExceptionHandlers.handleFilesetException(
                  OperationType.CREATE, request.getName(), schema, e));
    }
  }

  @POST
  @Path("{catalog}/schemas/{schema}/topics")
  @Produces("application/vnd.gravitino.v1+json")
  public Response createFilesetWithTag(
      @PathParam("metalake") String metalake,
      @PathParam("catalog") String catalog,
      @PathParam("schema") String schema,
      TopicWithTagsCreateRequest request) {
    LOG.info(
        "Received create topic with tags request: {}.{}.{}.{}: {}",
        metalake,
        catalog,
        schema,
        request.getName(),
        Arrays.toString(request.getTagsToAdd()));

    AtomicBoolean topicCreated = new AtomicBoolean(false);
    String fullName = String.join(".", catalog, schema, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            TopicDTO topicDTO = createTopic(metalake, catalog, schema, request);
            topicCreated.set(true);

            String[] topicTags =
                addTags(metalake, fullName, MetadataObject.Type.TOPIC, request.getTagsToAdd());
            LOG.info(
                "Tags associated with topic: {}.{}.{}.{}: {}",
                metalake,
                catalog,
                schema,
                topicDTO.name(),
                Arrays.toString(topicTags));

            return Utils.ok(new TopicWithTagsResponse(topicDTO, topicTags));
          });

    } catch (Exception e) {
      return rollbackIfNecessary(
          topicCreated.get(),
          fullName,
          e,
          () ->
              topicDispatcher.dropTopic(
                  NameIdentifierUtil.ofTopic(metalake, catalog, schema, request.getName())),
          () ->
              ExceptionHandlers.handleTopicException(
                  OperationType.CREATE, request.getName(), schema, e));
    }
  }

  @POST
  @Path("{catalog}/schemas/{schema}/models")
  @Produces("application/vnd.gravitino.v1+json")
  public Response registerModelWithTag(
      @PathParam("metalake") String metalake,
      @PathParam("catalog") String catalog,
      @PathParam("schema") String schema,
      ModelWithTagsCreateRequest request) {
    LOG.info(
        "Received register model with tags request: {}.{}.{}.{}: {}",
        metalake,
        catalog,
        schema,
        request.getName(),
        Arrays.toString(request.getTagsToAdd()));

    AtomicBoolean modelRegistered = new AtomicBoolean(false);
    String fullName = String.join(".", catalog, schema, request.getName());
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            ModelDTO modelDTO = registerModel(metalake, catalog, schema, request);
            modelRegistered.set(true);

            String[] modelTags =
                addTags(metalake, fullName, MetadataObject.Type.MODEL, request.getTagsToAdd());
            LOG.info(
                "Tags associated with model: {}.{}.{}.{}: {}",
                metalake,
                catalog,
                schema,
                modelDTO.name(),
                Arrays.toString(modelTags));

            return Utils.ok(new ModelWithTagsResponse(modelDTO, modelTags));
          });

    } catch (Exception e) {
      return rollbackIfNecessary(
          modelRegistered.get(),
          fullName,
          e,
          () ->
              modelDispatcher.deleteModel(
                  NameIdentifierUtil.ofModel(metalake, catalog, schema, request.getName())),
          () ->
              ExceptionHandlers.handleModelException(
                  OperationType.REGISTER, request.getName(), schema, e));
    }
  }

  private ModelDTO registerModel(
      String metalake, String catalog, String schema, ModelWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident = NameIdentifierUtil.ofModel(metalake, catalog, schema, request.getName());

    Model model =
        modelDispatcher.registerModel(ident, request.getComment(), request.getProperties());
    ModelDTO modelDTO = DTOConverters.toDTO(model);
    LOG.info("Model registered: {}.{}.{}.{}", metalake, catalog, schema, model.name());
    return modelDTO;
  }

  private TopicDTO createTopic(
      String metalake, String catalog, String schema, TopicWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident = NameIdentifierUtil.ofTopic(metalake, catalog, schema, request.getName());

    Topic topic =
        topicDispatcher.createTopic(
            ident,
            request.getComment(),
            null /* dataLayout, always null because it's not supported yet.*/,
            request.getProperties());
    TopicDTO topicDTO = DTOConverters.toDTO(topic);
    LOG.info("Topic created: {}.{}.{}.{}", metalake, catalog, schema, topic.name());
    return topicDTO;
  }

  private FilesetDTO createFileset(
      String metalake, String catalog, String schema, FilesetWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident =
        NameIdentifierUtil.ofFileset(metalake, catalog, schema, request.getName());

    // set storageLocation value as unnamed location if provided
    Map<String, String> tmpLocations =
        new HashMap<>(
            Optional.ofNullable(request.getStorageLocations()).orElse(Collections.emptyMap()));
    Optional.ofNullable(request.getStorageLocation())
        .ifPresent(loc -> tmpLocations.put(LOCATION_NAME_UNKNOWN, loc));
    Map<String, String> storageLocations = ImmutableMap.copyOf(tmpLocations);

    Fileset fileset =
        filesetDispatcher.createMultipleLocationFileset(
            ident,
            request.getComment(),
            Optional.ofNullable(request.getType()).orElse(Fileset.Type.MANAGED),
            storageLocations,
            request.getProperties());
    FilesetDTO filesetDTO = DTOConverters.toDTO(fileset);
    LOG.info("Fileset created: {}.{}.{}.{}", metalake, catalog, schema, request.getName());
    return filesetDTO;
  }

  private TableDTO createTable(
      String metalake, String catalog, String schema, TableWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident = NameIdentifierUtil.ofTable(metalake, catalog, schema, request.getName());

    Table table =
        tableDispatcher.createTable(
            ident,
            fromDTOs(request.getColumns()),
            request.getComment(),
            request.getProperties(),
            fromDTOs(request.getPartitioning()),
            fromDTO(request.getDistribution()),
            fromDTOs(request.getSortOrders()),
            fromDTOs(request.getIndexes()));
    TableDTO tableDTO = DTOConverters.toDTO(table);
    LOG.info("Table created: {}.{}.{}.{}", metalake, catalog, schema, request.getName());
    return tableDTO;
  }

  private CatalogDTO createCatalog(String metalake, CatalogWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident = NameIdentifierUtil.ofCatalog(metalake, request.getName());
    Catalog catalog =
        catalogDispatcher.createCatalog(
            ident,
            request.getType(),
            request.getProvider(),
            request.getComment(),
            request.getProperties());
    CatalogDTO catalogDTO = DTOConverters.toDTO(catalog);
    LOG.info("Catalog created: {}.{}", metalake, catalog.name());
    return catalogDTO;
  }

  private SchemaDTO createSchema(
      String metalake, String catalog, SchemaWithTagsCreateRequest request) {
    request.validate();
    NameIdentifier ident = NameIdentifierUtil.ofSchema(metalake, catalog, request.getName());
    Schema schema =
        schemaDispatcher.createSchema(ident, request.getComment(), request.getProperties());
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
    return tagDispatcher.associateTagsForMetadataObject(metalake, object, tags, new String[0]);
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
