/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.dto.util.DTOConverters.toDTO;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import com.datastrato.gravitino.dto.responses.CatalogListResponse;
import com.datastrato.gravitino.dto.responses.FilesetListResponse;
import com.datastrato.gravitino.dto.responses.ModelListResponse;
import com.datastrato.gravitino.dto.responses.SchemaListResponse;
import com.datastrato.gravitino.dto.responses.TableListResponse;
import com.datastrato.gravitino.dto.responses.TopicListResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.RepresentationDTO;
import org.apache.gravitino.dto.rel.SQLRepresentationDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/web/entities")
public class EntityOperations {

  private static final Logger LOG = LoggerFactory.getLogger(EntityOperations.class);

  @Context private HttpServletRequest httpRequest;

  private final CatalogDispatcher catalogDispatcher;
  private final DatastratoSchemaDispatcher schemaDispatcher;
  private final DatastratoTableDispatcher tableDispatcher;
  private final DatastratoFilesetDispatcher filesetDispatcher;
  private final DatastratoTopicDispatcher topicDispatcher;
  private final DatastratoModelDispatcher modelDispatcher;
  private final FunctionDispatcher functionDispatcher;
  private final DatastratoViewDispatcher viewDispatcher;

  @Inject
  public EntityOperations(
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      FunctionDispatcher functionDispatcher,
      ViewDispatcher viewDispatcher) {
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = (DatastratoSchemaDispatcher) schemaDispatcher;
    this.tableDispatcher = (DatastratoTableDispatcher) tableDispatcher;
    this.filesetDispatcher = (DatastratoFilesetDispatcher) filesetDispatcher;
    this.topicDispatcher = (DatastratoTopicDispatcher) topicDispatcher;
    this.modelDispatcher = (DatastratoModelDispatcher) modelDispatcher;
    this.functionDispatcher = functionDispatcher;
    this.viewDispatcher = (DatastratoViewDispatcher) viewDispatcher;
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "")
  public Response listEntities(
      @QueryParam("namespace") Namespace namespace,
      @QueryParam("catalogType") Catalog.Type catalogType,
      @DefaultValue("") @QueryParam("parentSchema") String parentSchema,
      @QueryParam("resultLimit") @DefaultValue("1000") int resultLimit) {
    LOG.info(
        "Received request to list entities for namespace: {}, parentSchema: {}",
        namespace,
        parentSchema);
    if (namespace == null || namespace.isEmpty()) {
      return Utils.illegalArguments(
          "Query param namespace cannot be empty", new IllegalArgumentException());
    }

    if (catalogType == null && namespace.length() != 1) {
      // catalogType is required except for listing catalogs
      return Utils.illegalArguments(
          "Query param catalogType cannot be empty", new IllegalArgumentException());
    }

    if (resultLimit <= 0) {
      return Utils.illegalArguments(
          "Result limit should be greater than 0", new IllegalArgumentException());
    }

    try {
      AuthorizationUtils.checkCurrentUser(namespace.level(0), PrincipalUtils.getCurrentUserName());
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    }

    try {
      return Utils.doAs(
          httpRequest, () -> doList(namespace, catalogType, parentSchema, resultLimit));
    } catch (Exception e) {
      return Utils.internalError("Error while listing entities", e);
    } catch (Throwable throwable) {
      return Utils.internalError("Unexpected error while listing entities", throwable);
    }
  }

  private Response doList(
      Namespace namespace, Catalog.Type catalogType, String parentSchema, int resultLimit) {
    if (StringUtils.isNotBlank(parentSchema)) {
      try {
        validateParentSchema(parentSchema);
      } catch (IllegalArgumentException e) {
        return Utils.illegalArguments(e.getMessage(), e);
      }

      if (namespace.length() == 2) {
        // list sub-schemas only under the given parentSchema
        try {
          return listSchemas(
              Namespace.of(namespace.level(0), namespace.level(1), parentSchema),
              catalogType,
              resultLimit);
        } catch (Exception e) {
          return ExceptionHandlers.handleSchemaException(
              OperationType.LIST, "", namespace.toString(), e);
        }
      } else if (namespace.length() == 3) {
        // list tables/functions/views + sub-schemas for hierarchical schema
        try {
          return listTables(namespace, parentSchema, resultLimit);
        } catch (Exception e) {
          return ExceptionHandlers.handleTableException(
              OperationType.LIST, "", namespace.toString(), e);
        }
      } else {
        return Utils.illegalArguments(
            "Query param namespace should have 2 or 3 levels when parentSchema is set",
            new IllegalArgumentException());
      }
    }

    switch (namespace.length()) {
      case 1:
        // list catalogs
        try {
          return listCatalogs(namespace, catalogType, resultLimit);
        } catch (Exception e) {
          return ExceptionHandlers.handleCatalogException(
              OperationType.LIST, "", namespace.toString(), e);
        }
      case 2:
        // list schemas
        try {
          return listSchemas(namespace, catalogType, resultLimit);
        } catch (Exception e) {
          return ExceptionHandlers.handleSchemaException(
              OperationType.LIST, "", namespace.toString(), e);
        }
      case 3:
        // list tables/topics/filesets/models
        switch (catalogType) {
          case RELATIONAL:
            try {
              return listTables(namespace, null, resultLimit);
            } catch (Exception e) {
              return ExceptionHandlers.handleTableException(
                  OperationType.LIST, "", namespace.toString(), e);
            }
          case MESSAGING:
            try {
              return listTopics(namespace, resultLimit);
            } catch (Exception e) {
              return ExceptionHandlers.handleTopicException(
                  OperationType.LIST, "", namespace.toString(), e);
            }
          case FILESET:
            try {
              return listFilesets(namespace, resultLimit);
            } catch (Exception e) {
              return ExceptionHandlers.handleFilesetException(
                  OperationType.LIST, "", namespace.toString(), e);
            }
          case MODEL:
            try {
              return listModels(namespace, resultLimit);
            } catch (Exception e) {
              return ExceptionHandlers.handleModelException(
                  OperationType.LIST, "", namespace.toString(), e);
            }
          default:
            return Utils.illegalArguments(
                "Unsupported catalog type: " + catalogType, new IllegalArgumentException());
        }
      default:
        return Utils.illegalArguments(
            "Namespace levels should be less than 4: " + namespace, new IllegalArgumentException());
    }
  }

  private void validateParentSchema(String parentSchema) {
    String separator = HierarchicalSchemaUtil.schemaSeparator();
    for (String segment : HierarchicalSchemaUtil.splitSchemaName(parentSchema, separator)) {
      if (StringUtils.isBlank(segment)) {
        throw new IllegalArgumentException(
            String.format(
                "The parentSchema '%s' contains an empty segment after splitting by '%s'.",
                parentSchema, separator));
      }
    }
  }

  private Response listCatalogs(Namespace namespace, Catalog.Type catalogType, int resultLimit) {
    Catalog[] catalogs = catalogDispatcher.listCatalogsInfo(namespace);
    catalogs =
        filterByExpression(
            namespace.level(0),
            AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.CATALOG,
            catalogs,
            catalog -> NameIdentifierUtil.ofCatalog(namespace.level(0), catalog.name()));
    Catalog[] selectedCatalogs =
        Arrays.stream(catalogs)
            .filter(catalog -> catalogType == null || catalog.type() == catalogType)
            .sorted(Comparator.comparing(Catalog::name))
            .limit(resultLimit)
            .toArray(Catalog[]::new);
    CatalogDTO[] catalogDTOs =
        Arrays.stream(selectedCatalogs).map(DTOConverters::toDTO).toArray(CatalogDTO[]::new);

    Map<String, Long> directChildCounts =
        listCatalogDirectChildCounts(namespace.level(0), selectedCatalogs);
    Response response = Utils.ok(new CatalogListResponse(catalogDTOs, directChildCounts));
    LOG.info("List {} catalog entities under namespace: {}", catalogDTOs.length, namespace);
    return response;
  }

  private Response listSchemas(Namespace namespace, Catalog.Type catalogType, int resultLimit) {
    SchemaDTO[] schemaDTOs = listSchemaDTOs(namespace, resultLimit);
    Map<String, Long> directChildCounts =
        listSchemaDirectChildCounts(namespace, catalogType, schemaDTOs);
    Response response = Utils.ok(new SchemaListResponse(schemaDTOs, directChildCounts));
    LOG.info("List {} schema entities under namespace: {}", schemaDTOs.length, namespace);
    return response;
  }

  private SchemaDTO[] listSchemaDTOs(Namespace namespace, int resultLimit) {
    NameIdentifier[] schemaIdents = listVisibleSchemaIdentifiers(namespace);
    // Schema entities are stored flat under the catalog namespace, even for parentSchema listings.
    Namespace schemaEntityNs = Namespace.of(namespace.level(0), namespace.level(1));
    List<SchemaEntity> schemaEntities = schemaDispatcher.listEntities(schemaEntityNs);
    ImmutableMap<String, SchemaEntity> nameToEntity =
        Maps.uniqueIndex(schemaEntities, SchemaEntity::name);

    return Arrays.stream(schemaIdents)
        .sorted(Comparator.comparing(NameIdentifier::name))
        .limit(resultLimit)
        .map(
            schemaIdent -> {
              SchemaDTO.Builder builder = SchemaDTO.builder().withName(schemaIdent.name());
              return Optional.ofNullable(nameToEntity.get(schemaIdent.name()))
                  .map(s -> builder.withAudit(toDTO(s.auditInfo())).build())
                  .orElse(builder.withAudit(AuditDTO.builder().build()).build());
            })
        .toArray(SchemaDTO[]::new);
  }

  private Response listTables(Namespace namespace, String parentSchema, int resultLimit) {
    NameIdentifier[] tableIdents = listVisibleTableIdentifiers(namespace);
    List<TableEntity> tableEntities;
    try {
      tableEntities = tableDispatcher.listEntities(namespace);
    } catch (NoSuchSchemaException e) {
      // If the schema is not created by Gravitino, there will be no table entities.
      tableEntities = Lists.newArrayList();
    }

    ImmutableMap<String, TableEntity> nameToTableEntity =
        Maps.uniqueIndex(tableEntities, TableEntity::name);

    TableDTO[] tableDTOs =
        Arrays.stream(tableIdents)
            .sorted(Comparator.comparing(NameIdentifier::name))
            .limit(resultLimit)
            .map(
                tableIdent -> {
                  TableDTO.Builder builder =
                      TableDTO.builder()
                          .withName(tableIdent.name())
                          // Use mock columns for listing tables, since frontend doesn't use columns
                          // info for displaying table list.
                          .withColumns(mockColumns());
                  return Optional.ofNullable(nameToTableEntity.get(tableIdent.name()))
                      .map(t -> builder.withAudit(toDTO(t.auditInfo())).build())
                      .orElse(builder.withAudit(AuditDTO.builder().build()).build());
                })
            .toArray(TableDTO[]::new);

    FunctionDTO[] functionDTOs = listFunctionDTOs(namespace, resultLimit);
    ViewDTO[] viewDTOs = listViewDTOs(namespace, resultLimit);

    SchemaDTO[] schemaDTOs = new SchemaDTO[0];
    if (StringUtils.isNotBlank(parentSchema)) {
      Namespace childSchemaNs = Namespace.of(namespace.level(0), namespace.level(1), parentSchema);
      try {
        schemaDTOs = listSchemaDTOs(childSchemaNs, resultLimit);
      } catch (Exception e) {
        LOG.warn("Failed to list child schemas under parentSchema: {}", parentSchema, e);
      }
    }

    Response response =
        Utils.ok(new TableListResponse(tableDTOs, functionDTOs, viewDTOs, schemaDTOs));
    LOG.info(
        "List {} table entities and {} child schemas under namespace: {}",
        tableDTOs.length,
        schemaDTOs.length,
        namespace);
    return response;
  }

  private Response listTopics(Namespace namespace, int resultLimit) {
    NameIdentifier[] topicIdents = listVisibleTopicIdentifiers(namespace);
    List<TopicEntity> topicEntities;
    try {
      topicEntities = topicDispatcher.listEntities(namespace);
    } catch (NoSuchSchemaException e) {
      // If the schema is not created by Gravitino, there will be no topic entities.
      topicEntities = Lists.newArrayList();
    }
    ImmutableMap<String, TopicEntity> nameToTopicEntity =
        Maps.uniqueIndex(topicEntities, TopicEntity::name);

    TopicDTO[] topicDTOs =
        Arrays.stream(topicIdents)
            .sorted(Comparator.comparing(NameIdentifier::name))
            .limit(resultLimit)
            .map(
                topicIdent -> {
                  TopicDTO.Builder builder = TopicDTO.builder().withName(topicIdent.name());
                  return Optional.ofNullable(nameToTopicEntity.get(topicIdent.name()))
                      .map(
                          t ->
                              builder
                                  .withComment(t.comment())
                                  .withAudit(toDTO(t.auditInfo()))
                                  .build())
                      .orElse(builder.withAudit(AuditDTO.builder().build()).build());
                })
            .toArray(TopicDTO[]::new);

    Response response =
        Utils.ok(new TopicListResponse(topicDTOs, listFunctionDTOs(namespace, resultLimit)));
    LOG.info("List {} topic entities under namespace: {}", topicDTOs.length, namespace);
    return response;
  }

  private Response listFilesets(Namespace namespace, int resultLimit) {
    // since fileset is managed by Gravitino, we can directly list them from store
    List<FilesetEntity> filesetEntities = listVisibleFilesetEntities(namespace);
    FilesetDTO[] filesetDTOs =
        filesetEntities.stream()
            .sorted(Comparator.comparing(FilesetEntity::name))
            .limit(resultLimit)
            .map(
                e ->
                    FilesetDTO.builder()
                        .name(e.name())
                        .comment(e.comment())
                        .type(e.filesetType())
                        .storageLocations(e.storageLocations())
                        .properties(e.properties())
                        .audit(toDTO(e.auditInfo()))
                        .build())
            .toArray(FilesetDTO[]::new);

    Response response =
        Utils.ok(new FilesetListResponse(filesetDTOs, listFunctionDTOs(namespace, resultLimit)));
    LOG.info("List {} fileset entities under namespace: {}", filesetDTOs.length, namespace);
    return response;
  }

  private Response listModels(Namespace namespace, int resultLimit) {
    // since model is managed by Gravitino, we can directly list them from store
    List<ModelEntity> modelEntities = listVisibleModelEntities(namespace);
    ModelDTO[] modelDTOs =
        modelEntities.stream()
            .sorted(Comparator.comparing(ModelEntity::name))
            .limit(resultLimit)
            .map(
                e ->
                    ModelDTO.builder()
                        .withName(e.name())
                        .withComment(e.comment())
                        .withLatestVersion(e.latestVersion())
                        .withProperties(e.properties())
                        .withAudit(toDTO(e.auditInfo()))
                        .build())
            .toArray(ModelDTO[]::new);

    Response response =
        Utils.ok(new ModelListResponse(modelDTOs, listFunctionDTOs(namespace, resultLimit)));
    LOG.info("List {} model entities under namespace: {}", modelDTOs.length, namespace);
    return response;
  }

  private FunctionDTO[] listFunctionDTOs(Namespace namespace, int resultLimit) {
    try {
      org.apache.gravitino.function.Function[] functions = listVisibleFunctions(namespace);
      return Arrays.stream(functions)
          .sorted(Comparator.comparing(org.apache.gravitino.function.Function::name))
          .limit(resultLimit)
          .map(DTOConverters::toDTO)
          .toArray(FunctionDTO[]::new);
    } catch (Exception e) {
      LOG.warn("Failed to list functions under namespace: {}", namespace, e);
      return new FunctionDTO[0];
    }
  }

  private ViewDTO[] listViewDTOs(Namespace namespace, int resultLimit) {
    try {
      NameIdentifier[] viewIdents = listVisibleViewIdentifiers(namespace);
      List<ViewEntity> viewEntities;
      try {
        viewEntities = viewDispatcher.listEntities(namespace);
      } catch (NoSuchSchemaException e) {
        // If the schema is not created by Gravitino, there will be no view entities.
        viewEntities = Lists.newArrayList();
      }

      ImmutableMap<String, ViewEntity> nameToViewEntity =
          Maps.uniqueIndex(viewEntities, ViewEntity::name);

      return Arrays.stream(viewIdents)
          .sorted(Comparator.comparing(NameIdentifier::name))
          .limit(resultLimit)
          .map(
              viewIdent -> {
                ViewDTO.Builder builder =
                    ViewDTO.builder()
                        .withName(viewIdent.name())
                        .withRepresentations(mockRepresentations())
                        .withAudit(AuditDTO.builder().build());
                return Optional.ofNullable(nameToViewEntity.get(viewIdent.name()))
                    .map(viewEntity -> builder.withAudit(toDTO(viewEntity.auditInfo())).build())
                    .orElse(builder.build());
              })
          .toArray(ViewDTO[]::new);
    } catch (Exception e) {
      LOG.warn("Failed to list views under namespace: {}", namespace, e);
      return new ViewDTO[0];
    }
  }

  private RepresentationDTO[] mockRepresentations() {
    return new RepresentationDTO[] {
      SQLRepresentationDTO.builder().withDialect("unavailable").withSql("UNAVAILABLE").build()
    };
  }

  private ColumnDTO[] mockColumns() {
    return new ColumnDTO[] {
      ColumnDTO.builder()
          .withName("mock_column")
          .withDataType(Types.IntegerType.get())
          .withComment("")
          .withNullable(true)
          .withAutoIncrement(false)
          .withDefaultValue(null)
          .build()
    };
  }

  private Map<String, Long> listCatalogDirectChildCounts(String metalake, Catalog[] catalogs) {
    Map<String, Long> directChildCounts = new LinkedHashMap<>();
    for (Catalog catalog : catalogs) {
      Namespace catalogNamespace = Namespace.of(metalake, catalog.name());
      try {
        long count = listVisibleSchemaIdentifiers(catalogNamespace).length;
        directChildCounts.put(catalog.name(), count);
      } catch (Exception e) {
        LOG.warn("Failed to count direct child schemas under catalog: {}", catalog.name(), e);
      }
    }
    return directChildCounts;
  }

  private Map<String, Long> listSchemaDirectChildCounts(
      Namespace namespace, Catalog.Type catalogType, SchemaDTO[] schemas) {
    Map<String, Long> directChildCounts = new LinkedHashMap<>();
    if (schemas.length == 0) {
      return directChildCounts;
    }

    Namespace catalogNamespace = Namespace.of(namespace.level(0), namespace.level(1));
    boolean supportsHierarchicalSchema;
    try {
      supportsHierarchicalSchema = schemaDispatcher.supportsHierarchicalSchema(catalogNamespace);
    } catch (Exception e) {
      LOG.warn(
          "Failed to determine hierarchical schema support under catalog: {}", catalogNamespace, e);
      return directChildCounts;
    }

    for (SchemaDTO schema : schemas) {
      Namespace schemaNamespace =
          Namespace.of(namespace.level(0), namespace.level(1), schema.name());
      try {
        directChildCounts.put(
            schema.name(),
            countVisibleSchemaDirectChildren(
                schemaNamespace, catalogType, supportsHierarchicalSchema));
      } catch (Exception e) {
        LOG.warn("Failed to count direct child entities under schema: {}", schema.name(), e);
      }
    }
    return directChildCounts;
  }

  private long countVisibleSchemaDirectChildren(
      Namespace namespace, Catalog.Type catalogType, boolean supportsHierarchicalSchema) {
    long count = countVisibleDirectSubSchemas(namespace, supportsHierarchicalSchema);
    switch (catalogType) {
      case RELATIONAL:
        return count
            + listVisibleTableIdentifiers(namespace).length
            + countVisibleFunctions(namespace)
            + countVisibleViews(namespace);
      case MESSAGING:
        return count
            + listVisibleTopicIdentifiers(namespace).length
            + countVisibleFunctions(namespace);
      case FILESET:
        return count
            + listVisibleFilesetEntities(namespace).size()
            + countVisibleFunctions(namespace);
      case MODEL:
        return count
            + listVisibleModelEntities(namespace).size()
            + countVisibleFunctions(namespace);
      default:
        throw new IllegalArgumentException("Unsupported catalog type: " + catalogType);
    }
  }

  private long countVisibleDirectSubSchemas(
      Namespace namespace, boolean supportsHierarchicalSchema) {
    if (!supportsHierarchicalSchema) {
      return 0;
    }
    return listVisibleSchemaIdentifiers(namespace).length;
  }

  private long countVisibleFunctions(Namespace namespace) {
    try {
      return listVisibleFunctions(namespace).length;
    } catch (UnsupportedOperationException e) {
      LOG.warn("Failed to count functions under namespace: {}", namespace, e);
      return 0;
    }
  }

  private long countVisibleViews(Namespace namespace) {
    try {
      return listVisibleViewIdentifiers(namespace).length;
    } catch (UnsupportedOperationException e) {
      LOG.warn("Failed to count views under namespace: {}", namespace, e);
      return 0;
    }
  }

  private NameIdentifier[] listVisibleSchemaIdentifiers(Namespace namespace) {
    NameIdentifier[] schemaIdents = schemaDispatcher.listSchemas(namespace);
    return filterByExpression(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.SCHEMA,
        schemaIdents,
        schemaIdent -> schemaIdent);
  }

  private NameIdentifier[] listVisibleTableIdentifiers(Namespace namespace) {
    NameIdentifier[] tableIdents = tableDispatcher.listTables(namespace);
    return filterByNameIdentifier(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.TABLE,
        tableIdents);
  }

  private NameIdentifier[] listVisibleTopicIdentifiers(Namespace namespace) {
    NameIdentifier[] topicIdents = topicDispatcher.listTopics(namespace);
    return filterByNameIdentifier(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_TOPICS_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.TOPIC,
        topicIdents);
  }

  private NameIdentifier[] listVisibleViewIdentifiers(Namespace namespace) {
    NameIdentifier[] viewIdents = viewDispatcher.listViews(namespace);
    return filterByNameIdentifier(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_VIEW_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.VIEW,
        viewIdents);
  }

  private org.apache.gravitino.function.Function[] listVisibleFunctions(Namespace namespace) {
    org.apache.gravitino.function.Function[] functions =
        functionDispatcher.listFunctionInfos(namespace);
    return filterByExpression(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_FUNCTION_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.FUNCTION,
        functions,
        function ->
            NameIdentifierUtil.ofFunction(
                namespace.level(0), namespace.level(1), namespace.level(2), function.name()));
  }

  private List<FilesetEntity> listVisibleFilesetEntities(Namespace namespace) {
    List<FilesetEntity> filesetEntities = filesetDispatcher.listEntities(namespace);
    return Arrays.asList(
        filterByExpression(
            namespace.level(0),
            AuthorizationExpressionConstants.FILTER_FILESET_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.FILESET,
            filesetEntities.toArray(new FilesetEntity[0]),
            fileset ->
                NameIdentifierUtil.ofFileset(
                    namespace.level(0), namespace.level(1), namespace.level(2), fileset.name())));
  }

  private List<ModelEntity> listVisibleModelEntities(Namespace namespace) {
    List<ModelEntity> modelEntities = modelDispatcher.listEntities(namespace);
    return Arrays.asList(
        filterByExpression(
            namespace.level(0),
            AuthorizationExpressionConstants.FILTER_MODEL_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.MODEL,
            modelEntities.toArray(new ModelEntity[0]),
            model ->
                NameIdentifierUtil.ofModel(
                    namespace.level(0), namespace.level(1), namespace.level(2), model.name())));
  }

  private NameIdentifier[] filterByNameIdentifier(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      NameIdentifier[] identifiers) {
    return filterByExpression(metalake, expression, entityType, identifiers, id -> id);
  }

  private NameIdentifier[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      NameIdentifier[] identifiers,
      Function<NameIdentifier, NameIdentifier> toNameIdentifier) {
    try {
      return MetadataAuthzHelper.filterByExpression(
          metalake, expression, entityType, identifiers, toNameIdentifier);
    } catch (IllegalArgumentException e) {
      if (!isMetadataAuthorizationReady()) {
        LOG.warn(
            "Skip metadata authorization filtering for {} due to uninitialized GravitinoEnv.",
            entityType);
        return identifiers;
      }
      throw e;
    }
  }

  private <E> E[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      E[] entities,
      Function<E, NameIdentifier> toNameIdentifier) {
    try {
      return MetadataAuthzHelper.filterByExpression(
          metalake, expression, entityType, entities, toNameIdentifier);
    } catch (IllegalArgumentException e) {
      if (!isMetadataAuthorizationReady()) {
        LOG.warn(
            "Skip metadata authorization filtering for {} due to uninitialized GravitinoEnv.",
            entityType);
        return entities;
      }
      throw e;
    }
  }

  private boolean isMetadataAuthorizationReady() {
    return GravitinoEnv.getInstance().config() != null
        && GravitinoEnv.getInstance().accessControlDispatcher() != null;
  }
}
