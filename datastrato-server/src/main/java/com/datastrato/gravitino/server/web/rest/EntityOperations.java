/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static com.datastrato.gravitino.server.web.rest.MetadataListingHelper.filterByExpression;
import static org.apache.gravitino.dto.util.DTOConverters.toDTO;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import com.datastrato.gravitino.dto.DirectChildCountDTO;
import com.datastrato.gravitino.dto.DirectChildCountState;
import com.datastrato.gravitino.dto.ExtendedCatalogDTO;
import com.datastrato.gravitino.dto.ExtendedMetalakeDTO;
import com.datastrato.gravitino.dto.ExtendedSchemaDTO;
import com.datastrato.gravitino.dto.file.ExtendedFilesetDTO;
import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.datastrato.gravitino.dto.messaging.ExtendedTopicDTO;
import com.datastrato.gravitino.dto.model.ExtendedModelDTO;
import com.datastrato.gravitino.dto.rel.ExtendedTableDTO;
import com.datastrato.gravitino.dto.rel.ExtendedViewDTO;
import com.datastrato.gravitino.dto.responses.CatalogListResponse;
import com.datastrato.gravitino.dto.responses.FilesetListResponse;
import com.datastrato.gravitino.dto.responses.MetalakeListResponse;
import com.datastrato.gravitino.dto.responses.ModelListResponse;
import com.datastrato.gravitino.dto.responses.SchemaListResponse;
import com.datastrato.gravitino.dto.responses.TableListResponse;
import com.datastrato.gravitino.dto.responses.TopicListResponse;
import com.datastrato.gravitino.metrics.DirectChildCountMetricNames;
import com.datastrato.gravitino.metrics.dto.MetricState;
import com.datastrato.gravitino.metrics.storage.relational.MetricPO;
import com.datastrato.gravitino.metrics.storage.relational.service.CurrentMetricsSnapshot;
import com.datastrato.gravitino.metrics.storage.relational.service.MetricDataService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Metalake;
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
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.RepresentationDTO;
import org.apache.gravitino.dto.rel.SQLRepresentationDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/web/entities")
public class EntityOperations {

  private static final Logger LOG = LoggerFactory.getLogger(EntityOperations.class);

  @Context private HttpServletRequest httpRequest;

  private final MetalakeDispatcher metalakeDispatcher;
  private final CatalogDispatcher catalogDispatcher;
  private final DatastratoSchemaDispatcher schemaDispatcher;
  private final DatastratoTableDispatcher tableDispatcher;
  private final DatastratoFilesetDispatcher filesetDispatcher;
  private final DatastratoTopicDispatcher topicDispatcher;
  private final DatastratoModelDispatcher modelDispatcher;
  private final FunctionDispatcher functionDispatcher;
  private final DatastratoViewDispatcher viewDispatcher;
  private final MetricDataService metricDataService;

  @Inject
  public EntityOperations(
      MetalakeDispatcher metalakeDispatcher,
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      FunctionDispatcher functionDispatcher,
      ViewDispatcher viewDispatcher,
      MetricDataService metricDataService) {
    this.metalakeDispatcher = metalakeDispatcher;
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = (DatastratoSchemaDispatcher) schemaDispatcher;
    this.tableDispatcher = (DatastratoTableDispatcher) tableDispatcher;
    this.filesetDispatcher = (DatastratoFilesetDispatcher) filesetDispatcher;
    this.topicDispatcher = (DatastratoTopicDispatcher) topicDispatcher;
    this.modelDispatcher = (DatastratoModelDispatcher) modelDispatcher;
    this.functionDispatcher = functionDispatcher;
    this.viewDispatcher = (DatastratoViewDispatcher) viewDispatcher;
    this.metricDataService = metricDataService;
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
    if (resultLimit <= 0) {
      return Utils.illegalArguments(
          "Result limit should be greater than 0", new IllegalArgumentException());
    }

    if (namespace == null || namespace.isEmpty()) {
      if (catalogType != null || StringUtils.isNotBlank(parentSchema)) {
        return Utils.illegalArguments(
            "Query params catalogType and parentSchema cannot be set when namespace is empty",
            new IllegalArgumentException());
      }

      try {
        return Utils.doAs(httpRequest, () -> listMetalakes(resultLimit));
      } catch (Exception e) {
        return ExceptionHandlers.handleMetalakeException(
            OperationType.LIST, Namespace.empty().toString(), e);
      }
    }

    if (catalogType == null && namespace.length() != 1) {
      // catalogType is required except for listing catalogs
      return Utils.illegalArguments(
          "Query param catalogType cannot be empty", new IllegalArgumentException());
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
              Namespace.of(namespace.level(0), namespace.level(1), parentSchema), resultLimit);
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
          return listSchemas(namespace, resultLimit);
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

  private Response listMetalakes(int resultLimit) {
    Metalake[] metalakes =
        MetadataAuthzHelper.filterMetalakes(
            metalakeDispatcher.listMetalakes(),
            AuthorizationExpressionConstants.LOAD_METALAKE_AUTHORIZATION_EXPRESSION);
    Metalake[] selectedMetalakes =
        Arrays.stream(metalakes)
            .sorted(Comparator.comparing(Metalake::name))
            .limit(resultLimit)
            .toArray(Metalake[]::new);
    Map<String, Long> directChildCounts = listMetalakeDirectChildCounts(selectedMetalakes);

    ExtendedMetalakeDTO[] extendedMetalakes =
        Arrays.stream(selectedMetalakes)
            .map(
                metalake ->
                    new ExtendedMetalakeDTO(
                        DTOConverters.toDTO(metalake), directChildCounts.get(metalake.name())))
            .toArray(ExtendedMetalakeDTO[]::new);

    Response response = Utils.ok(new MetalakeListResponse(extendedMetalakes));
    LOG.info("List {} Metalake entities", extendedMetalakes.length);
    return response;
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

    String metalake = namespace.level(0);
    List<MetadataObject> catalogObjects =
        Arrays.stream(selectedCatalogs)
            .map(c -> MetadataObjects.of(List.of(c.name()), MetadataObject.Type.CATALOG))
            .collect(Collectors.toList());

    TagPolicyEnrichmentHelper.Result tagPolicies =
        getVisibleTagPoliciesWithInheritance(metalake, catalogObjects, new LinkedHashMap<>());
    Map<MetadataObject, TagDTO[]> tagsMap = tagPolicies.tags();
    Map<MetadataObject, PolicyDTO[]> policiesMap = tagPolicies.policies();

    Map<String, DirectChildCountDTO> directChildCounts =
        listCatalogDirectChildCounts(metalake, selectedCatalogs);

    ExtendedCatalogDTO[] extendedCatalogs = new ExtendedCatalogDTO[selectedCatalogs.length];
    for (int i = 0; i < selectedCatalogs.length; i++) {
      Catalog c = selectedCatalogs[i];
      MetadataObject obj = catalogObjects.get(i);
      TagDTO[] tags = tagsMap.getOrDefault(obj, new TagDTO[0]);
      PolicyDTO[] policies = policiesMap.getOrDefault(obj, new PolicyDTO[0]);
      DirectChildCountDTO count = directChildCounts.get(c.name());
      extendedCatalogs[i] = new ExtendedCatalogDTO(DTOConverters.toDTO(c), tags, policies, count);
    }

    Response response = Utils.ok(new CatalogListResponse(extendedCatalogs));
    LOG.info("List {} catalog entities under namespace: {}", extendedCatalogs.length, namespace);
    return response;
  }

  private Response listSchemas(Namespace namespace, int resultLimit) {
    String metalake = namespace.level(0);

    Map<MetadataObject, Optional<Long>> knownEntityIds = new LinkedHashMap<>();
    List<MetadataObject> schemaObjects = new ArrayList<>();
    SchemaDTO[] schemaDTOs = listSchemaDTOs(namespace, resultLimit, knownEntityIds, schemaObjects);
    Map<String, DirectChildCountDTO> directChildCounts =
        listSchemaDirectChildCounts(namespace, schemaDTOs);

    TagPolicyEnrichmentHelper.Result tagPolicies =
        getVisibleTagPoliciesWithInheritance(metalake, schemaObjects, knownEntityIds);
    Map<MetadataObject, TagDTO[]> tagsMap = tagPolicies.tags();
    Map<MetadataObject, PolicyDTO[]> policiesMap = tagPolicies.policies();

    ExtendedSchemaDTO[] extendedSchemas = new ExtendedSchemaDTO[schemaDTOs.length];
    for (int i = 0; i < schemaDTOs.length; i++) {
      SchemaDTO s = schemaDTOs[i];
      MetadataObject obj = schemaObjects.get(i);
      DirectChildCountDTO count = directChildCounts.get(s.name());
      extendedSchemas[i] =
          new ExtendedSchemaDTO(
              s,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]),
              count);
    }

    Response response = Utils.ok(new SchemaListResponse(extendedSchemas));
    LOG.info("List {} schema entities under namespace: {}", extendedSchemas.length, namespace);
    return response;
  }

  private SchemaDTO[] listSchemaDTOs(
      Namespace namespace,
      int resultLimit,
      Map<MetadataObject, Optional<Long>> knownEntityIds,
      List<MetadataObject> schemaObjects) {
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
              MetadataObject schemaObject =
                  MetadataObjects.of(
                      List.of(namespace.level(1), schemaIdent.name()), MetadataObject.Type.SCHEMA);
              schemaObjects.add(schemaObject);
              Optional<SchemaEntity> schemaEntity =
                  Optional.ofNullable(nameToEntity.get(schemaIdent.name()));
              knownEntityIds.put(schemaObject, schemaEntity.map(SchemaEntity::id));
              MetadataObjectUtil.getParentMetadataObjects(schemaObject).stream()
                  .filter(parent -> parent.type() == MetadataObject.Type.SCHEMA)
                  .forEach(
                      parent ->
                          knownEntityIds.putIfAbsent(
                              parent,
                              Optional.ofNullable(nameToEntity.get(parent.name()))
                                  .map(SchemaEntity::id)));
              SchemaDTO.Builder builder = SchemaDTO.builder().withName(schemaIdent.name());
              return schemaEntity
                  .map(s -> builder.withAudit(toDTO(s.auditInfo())).build())
                  .orElse(builder.withAudit(AuditDTO.builder().build()).build());
            })
        .toArray(SchemaDTO[]::new);
  }

  private Response listTables(Namespace namespace, String parentSchema, int resultLimit) {
    String metalake = namespace.level(0);
    String catalogName = namespace.level(1);
    String schemaName = namespace.level(2);
    Map<MetadataObject, Optional<Long>> knownEntityIds = new LinkedHashMap<>();

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

    List<MetadataObject> tableObjects = new ArrayList<>();
    TableDTO[] tableDTOs =
        Arrays.stream(tableIdents)
            .sorted(Comparator.comparing(NameIdentifier::name))
            .limit(resultLimit)
            .map(
                tableIdent -> {
                  MetadataObject object =
                      MetadataObjects.of(
                          List.of(catalogName, schemaName, tableIdent.name()),
                          MetadataObject.Type.TABLE);
                  tableObjects.add(object);
                  knownEntityIds.put(
                      object,
                      Optional.ofNullable(nameToTableEntity.get(tableIdent.name()))
                          .map(TableEntity::id));
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

    List<MetadataObject> functionObjects = new ArrayList<>();
    FunctionDTO[] functionDTOs =
        listFunctionDTOs(namespace, resultLimit, knownEntityIds, functionObjects);
    List<MetadataObject> viewObjects = new ArrayList<>();
    ViewDTO[] viewDTOs = listViewDTOs(namespace, resultLimit, knownEntityIds, viewObjects);

    SchemaDTO[] schemaDTOs = new SchemaDTO[0];
    List<MetadataObject> schemaObjects = new ArrayList<>();
    if (StringUtils.isNotBlank(parentSchema)) {
      Namespace childSchemaNs = Namespace.of(namespace.level(0), namespace.level(1), parentSchema);
      try {
        schemaDTOs = listSchemaDTOs(childSchemaNs, resultLimit, knownEntityIds, schemaObjects);
      } catch (Exception e) {
        LOG.warn("Failed to list child schemas under parentSchema: {}", parentSchema, e);
      }
    }

    // Build MetadataObjects for batch fetch
    List<MetadataObject> objectsToFetch = new ArrayList<>();
    objectsToFetch.addAll(tableObjects);
    objectsToFetch.addAll(functionObjects);
    objectsToFetch.addAll(viewObjects);
    objectsToFetch.addAll(schemaObjects);

    TagPolicyEnrichmentHelper.Result tagPolicies =
        getVisibleTagPoliciesWithInheritance(metalake, objectsToFetch, knownEntityIds);
    Map<MetadataObject, TagDTO[]> tagsMap = tagPolicies.tags();
    Map<MetadataObject, PolicyDTO[]> policiesMap = tagPolicies.policies();

    ExtendedTableDTO[] extendedTables = new ExtendedTableDTO[tableDTOs.length];
    for (int i = 0; i < tableDTOs.length; i++) {
      TableDTO t = tableDTOs[i];
      MetadataObject obj = tableObjects.get(i);
      extendedTables[i] =
          new ExtendedTableDTO(
              t,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    ExtendedFunctionDTO[] extendedFunctions = new ExtendedFunctionDTO[functionDTOs.length];
    for (int i = 0; i < functionDTOs.length; i++) {
      FunctionDTO f = functionDTOs[i];
      MetadataObject obj = functionObjects.get(i);
      extendedFunctions[i] =
          new ExtendedFunctionDTO(
              f,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    ExtendedViewDTO[] extendedViews = new ExtendedViewDTO[viewDTOs.length];
    for (int i = 0; i < viewDTOs.length; i++) {
      ViewDTO v = viewDTOs[i];
      MetadataObject obj = viewObjects.get(i);
      extendedViews[i] =
          new ExtendedViewDTO(
              v,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    // Only relational catalogs expose tables, and Iceberg is currently the only catalog that
    // supports hierarchical schemas.
    Map<String, DirectChildCountDTO> childSchemaCounts =
        listSchemaDirectChildCounts(namespace, schemaDTOs);
    ExtendedSchemaDTO[] extendedSchemas = new ExtendedSchemaDTO[schemaDTOs.length];
    for (int i = 0; i < schemaDTOs.length; i++) {
      SchemaDTO s = schemaDTOs[i];
      MetadataObject obj = schemaObjects.get(i);
      DirectChildCountDTO count = childSchemaCounts.get(s.name());
      extendedSchemas[i] =
          new ExtendedSchemaDTO(
              s,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]),
              count);
    }

    Response response =
        Utils.ok(
            new TableListResponse(
                extendedTables, extendedFunctions, extendedViews, extendedSchemas));
    LOG.info(
        "List {} table entities and {} child schemas under namespace: {}",
        extendedTables.length,
        extendedSchemas.length,
        namespace);
    return response;
  }

  private Response listTopics(Namespace namespace, int resultLimit) {
    String metalake = namespace.level(0);
    String catalogName = namespace.level(1);
    String schemaName = namespace.level(2);
    Map<MetadataObject, Optional<Long>> knownEntityIds = new LinkedHashMap<>();

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

    List<MetadataObject> functionObjects = new ArrayList<>();
    FunctionDTO[] functionDTOs =
        listFunctionDTOs(namespace, resultLimit, knownEntityIds, functionObjects);

    List<MetadataObject> topicObjects = new ArrayList<>();
    Arrays.stream(topicDTOs)
        .forEach(
            t -> {
              MetadataObject object =
                  MetadataObjects.of(
                      List.of(catalogName, schemaName, t.name()), MetadataObject.Type.TOPIC);
              topicObjects.add(object);
              knownEntityIds.put(
                  object,
                  Optional.ofNullable(nameToTopicEntity.get(t.name())).map(TopicEntity::id));
            });
    List<MetadataObject> objectsToFetch = new ArrayList<>();
    objectsToFetch.addAll(topicObjects);
    objectsToFetch.addAll(functionObjects);

    TagPolicyEnrichmentHelper.Result tagPolicies =
        getVisibleTagPoliciesWithInheritance(metalake, objectsToFetch, knownEntityIds);
    Map<MetadataObject, TagDTO[]> tagsMap = tagPolicies.tags();
    Map<MetadataObject, PolicyDTO[]> policiesMap = tagPolicies.policies();

    ExtendedTopicDTO[] extendedTopics = new ExtendedTopicDTO[topicDTOs.length];
    for (int i = 0; i < topicDTOs.length; i++) {
      TopicDTO t = topicDTOs[i];
      MetadataObject obj = topicObjects.get(i);
      extendedTopics[i] =
          new ExtendedTopicDTO(
              t,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    ExtendedFunctionDTO[] extendedFunctions = new ExtendedFunctionDTO[functionDTOs.length];
    for (int i = 0; i < functionDTOs.length; i++) {
      FunctionDTO f = functionDTOs[i];
      MetadataObject obj = functionObjects.get(i);
      extendedFunctions[i] =
          new ExtendedFunctionDTO(
              f,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    Response response = Utils.ok(new TopicListResponse(extendedTopics, extendedFunctions));
    LOG.info("List {} topic entities under namespace: {}", extendedTopics.length, namespace);
    return response;
  }

  private Response listFilesets(Namespace namespace, int resultLimit) {
    String metalake = namespace.level(0);
    String catalogName = namespace.level(1);
    String schemaName = namespace.level(2);
    Map<MetadataObject, Optional<Long>> knownEntityIds = new LinkedHashMap<>();

    List<FilesetEntity> filesetEntities = listVisibleFilesetEntities(namespace);
    List<MetadataObject> filesetObjects = new ArrayList<>();
    FilesetDTO[] filesetDTOs =
        filesetEntities.stream()
            .sorted(Comparator.comparing(FilesetEntity::name))
            .limit(resultLimit)
            .map(
                e -> {
                  MetadataObject object =
                      MetadataObjects.of(
                          List.of(catalogName, schemaName, e.name()), MetadataObject.Type.FILESET);
                  filesetObjects.add(object);
                  knownEntityIds.put(object, Optional.of(e.id()));
                  return FilesetDTO.builder()
                      .name(e.name())
                      .comment(e.comment())
                      .type(e.filesetType())
                      .storageLocations(e.storageLocations())
                      .properties(e.properties())
                      .audit(toDTO(e.auditInfo()))
                      .build();
                })
            .toArray(FilesetDTO[]::new);

    List<MetadataObject> functionObjects = new ArrayList<>();
    FunctionDTO[] functionDTOs =
        listFunctionDTOs(namespace, resultLimit, knownEntityIds, functionObjects);

    List<MetadataObject> objectsToFetch = new ArrayList<>();
    objectsToFetch.addAll(filesetObjects);
    objectsToFetch.addAll(functionObjects);

    TagPolicyEnrichmentHelper.Result tagPolicies =
        getVisibleTagPoliciesWithInheritance(metalake, objectsToFetch, knownEntityIds);
    Map<MetadataObject, TagDTO[]> tagsMap = tagPolicies.tags();
    Map<MetadataObject, PolicyDTO[]> policiesMap = tagPolicies.policies();

    ExtendedFilesetDTO[] extendedFilesets = new ExtendedFilesetDTO[filesetDTOs.length];
    for (int i = 0; i < filesetDTOs.length; i++) {
      FilesetDTO f = filesetDTOs[i];
      MetadataObject obj = filesetObjects.get(i);
      extendedFilesets[i] =
          new ExtendedFilesetDTO(
              f,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    ExtendedFunctionDTO[] extendedFunctions = new ExtendedFunctionDTO[functionDTOs.length];
    for (int i = 0; i < functionDTOs.length; i++) {
      FunctionDTO f = functionDTOs[i];
      MetadataObject obj = functionObjects.get(i);
      extendedFunctions[i] =
          new ExtendedFunctionDTO(
              f,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    Response response = Utils.ok(new FilesetListResponse(extendedFilesets, extendedFunctions));
    LOG.info("List {} fileset entities under namespace: {}", extendedFilesets.length, namespace);
    return response;
  }

  private Response listModels(Namespace namespace, int resultLimit) {
    String metalake = namespace.level(0);
    String catalogName = namespace.level(1);
    String schemaName = namespace.level(2);
    Map<MetadataObject, Optional<Long>> knownEntityIds = new LinkedHashMap<>();

    List<ModelEntity> modelEntities = listVisibleModelEntities(namespace);
    List<MetadataObject> modelObjects = new ArrayList<>();
    ModelDTO[] modelDTOs =
        modelEntities.stream()
            .sorted(Comparator.comparing(ModelEntity::name))
            .limit(resultLimit)
            .map(
                e -> {
                  MetadataObject object =
                      MetadataObjects.of(
                          List.of(catalogName, schemaName, e.name()), MetadataObject.Type.MODEL);
                  modelObjects.add(object);
                  knownEntityIds.put(object, Optional.of(e.id()));
                  return ModelDTO.builder()
                      .withName(e.name())
                      .withComment(e.comment())
                      .withLatestVersion(e.latestVersion())
                      .withProperties(e.properties())
                      .withAudit(toDTO(e.auditInfo()))
                      .build();
                })
            .toArray(ModelDTO[]::new);

    List<MetadataObject> functionObjects = new ArrayList<>();
    FunctionDTO[] functionDTOs =
        listFunctionDTOs(namespace, resultLimit, knownEntityIds, functionObjects);

    List<MetadataObject> objectsToFetch = new ArrayList<>();
    objectsToFetch.addAll(modelObjects);
    objectsToFetch.addAll(functionObjects);

    TagPolicyEnrichmentHelper.Result tagPolicies =
        getVisibleTagPoliciesWithInheritance(metalake, objectsToFetch, knownEntityIds);
    Map<MetadataObject, TagDTO[]> tagsMap = tagPolicies.tags();
    Map<MetadataObject, PolicyDTO[]> policiesMap = tagPolicies.policies();

    ExtendedModelDTO[] extendedModels = new ExtendedModelDTO[modelDTOs.length];
    for (int i = 0; i < modelDTOs.length; i++) {
      ModelDTO m = modelDTOs[i];
      MetadataObject obj = modelObjects.get(i);
      extendedModels[i] =
          new ExtendedModelDTO(
              m,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    ExtendedFunctionDTO[] extendedFunctions = new ExtendedFunctionDTO[functionDTOs.length];
    for (int i = 0; i < functionDTOs.length; i++) {
      FunctionDTO f = functionDTOs[i];
      MetadataObject obj = functionObjects.get(i);
      extendedFunctions[i] =
          new ExtendedFunctionDTO(
              f,
              tagsMap.getOrDefault(obj, new TagDTO[0]),
              policiesMap.getOrDefault(obj, new PolicyDTO[0]));
    }

    Response response = Utils.ok(new ModelListResponse(extendedModels, extendedFunctions));
    LOG.info("List {} model entities under namespace: {}", extendedModels.length, namespace);
    return response;
  }

  private TagPolicyEnrichmentHelper.Result getVisibleTagPoliciesWithInheritance(
      String metalake,
      List<MetadataObject> objects,
      Map<MetadataObject, Optional<Long>> knownEntityIds) {
    return TagPolicyEnrichmentHelper.getVisibleTagPoliciesWithInheritance(
        metalake,
        objects,
        knownEntityIds,
        tags -> filterTagsByAuth(metalake, tags),
        policies -> filterPoliciesByAuth(metalake, policies));
  }

  private FunctionDTO[] listFunctionDTOs(
      Namespace namespace,
      int resultLimit,
      Map<MetadataObject, Optional<Long>> knownEntityIds,
      List<MetadataObject> functionObjects) {
    try {
      org.apache.gravitino.function.Function[] functions = listVisibleFunctions(namespace);
      return Arrays.stream(functions)
          .sorted(Comparator.comparing(org.apache.gravitino.function.Function::name))
          .limit(resultLimit)
          .map(
              function -> {
                MetadataObject object =
                    MetadataObjects.of(
                        List.of(namespace.level(1), namespace.level(2), function.name()),
                        MetadataObject.Type.FUNCTION);
                functionObjects.add(object);
                if (function instanceof HasIdentifier entity) {
                  knownEntityIds.put(object, Optional.of(entity.id()));
                }
                return DTOConverters.toDTO(function);
              })
          .toArray(FunctionDTO[]::new);
    } catch (Exception e) {
      functionObjects.clear();
      LOG.warn("Failed to list functions under namespace: {}", namespace, e);
      return new FunctionDTO[0];
    }
  }

  private ViewDTO[] listViewDTOs(
      Namespace namespace,
      int resultLimit,
      Map<MetadataObject, Optional<Long>> knownEntityIds,
      List<MetadataObject> viewObjects) {
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
                MetadataObject object =
                    MetadataObjects.of(
                        List.of(namespace.level(1), namespace.level(2), viewIdent.name()),
                        MetadataObject.Type.VIEW);
                viewObjects.add(object);
                knownEntityIds.put(
                    object,
                    Optional.ofNullable(nameToViewEntity.get(viewIdent.name()))
                        .map(ViewEntity::id));
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
      viewObjects.clear();
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

  private Map<String, DirectChildCountDTO> listCatalogDirectChildCounts(
      String metalake, Catalog[] catalogs) {
    Map<String, String> metricNameByCatalog = new LinkedHashMap<>();
    Arrays.stream(catalogs)
        .forEach(
            catalog ->
                metricNameByCatalog.put(
                    catalog.name(), DirectChildCountMetricNames.forCatalog(catalog.name())));
    return getDirectChildCounts(metalake, metricNameByCatalog);
  }

  private Map<String, Long> listMetalakeDirectChildCounts(Metalake[] metalakes) {
    Map<String, Long> directChildCounts = new LinkedHashMap<>();
    for (Metalake metalake : metalakes) {
      try {
        long count = listVisibleCatalogIdentifiers(metalake.name()).length;
        directChildCounts.put(metalake.name(), count);
      } catch (Exception e) {
        LOG.warn("Failed to count direct child catalogs under Metalake: {}", metalake.name(), e);
      }
    }
    return directChildCounts;
  }

  private Map<String, DirectChildCountDTO> listSchemaDirectChildCounts(
      Namespace namespace, SchemaDTO[] schemas) {
    Map<String, String> metricNameBySchema = new LinkedHashMap<>();
    Arrays.stream(schemas)
        .forEach(
            schema ->
                metricNameBySchema.put(
                    schema.name(),
                    DirectChildCountMetricNames.forSchema(namespace.level(1), schema.name())));
    return getDirectChildCounts(namespace.level(0), metricNameBySchema);
  }

  private Map<String, DirectChildCountDTO> getDirectChildCounts(
      String metalake, Map<String, String> metricNameByEntity) {
    if (metricNameByEntity.isEmpty()) {
      return new LinkedHashMap<>();
    }

    CurrentMetricsSnapshot currentMetrics;
    try {
      currentMetrics =
          metricDataService.getCurrentMetrics(
              metalake,
              PrincipalUtils.getCurrentUserName(),
              metricNameByEntity.values().toArray(new String[0]));
    } catch (Exception e) {
      LOG.warn("Failed to query current direct-child-count metrics for metalake {}", metalake, e);
      return unavailableDirectChildCounts(metricNameByEntity, false);
    }

    Map<String, MetricPO> metricByName = new LinkedHashMap<>();
    currentMetrics.getMetrics().forEach(metric -> metricByName.put(metric.getMetricName(), metric));
    Map<String, DirectChildCountReading> readings = new LinkedHashMap<>();
    boolean needsRefresh = false;
    for (Map.Entry<String, String> expected : metricNameByEntity.entrySet()) {
      DirectChildCountReading reading =
          toDirectChildCountReading(metricByName.get(expected.getValue()));
      readings.put(expected.getKey(), reading);
      needsRefresh |= reading.needsRefresh;
    }

    boolean refreshPending = currentMetrics.getDirty() != null;
    if (needsRefresh && !refreshPending) {
      try {
        metricDataService.markMetalakeDirty(
            currentMetrics.getMetalakeId(), System.currentTimeMillis());
        refreshPending = true;
      } catch (Exception e) {
        LOG.warn(
            "Failed to mark metalake {} dirty after finding incomplete direct-child-count metrics",
            metalake,
            e);
      }
    }

    Map<String, DirectChildCountDTO> result = new LinkedHashMap<>();
    boolean finalRefreshPending = refreshPending;
    readings.forEach((entity, reading) -> result.put(entity, reading.toDTO(finalRefreshPending)));
    return result;
  }

  private static DirectChildCountReading toDirectChildCountReading(MetricPO metric) {
    if (metric == null) {
      return DirectChildCountReading.missing();
    }

    Long updatedAt = metric.getCreatedTime() == null ? null : metric.getCreatedTime().getTime();
    MetricState metricState = metric.getMetricState();
    if (metricState == MetricState.COMPLETE
        && updatedAt != null
        && isValidDirectChildCount(metric.getMetricValue())) {
      return new DirectChildCountReading(
          metric.getMetricValue().longValue(), DirectChildCountState.COMPLETE, updatedAt, false);
    }
    DirectChildCountState state =
        metricState == MetricState.PARTIAL
            ? DirectChildCountState.PARTIAL
            : DirectChildCountState.UNAVAILABLE;
    return new DirectChildCountReading(null, state, updatedAt, true);
  }

  private static boolean isValidDirectChildCount(Double value) {
    return value != null
        && Double.isFinite(value)
        && value >= 0
        && value <= Long.MAX_VALUE
        && value == Math.rint(value);
  }

  private static Map<String, DirectChildCountDTO> unavailableDirectChildCounts(
      Map<String, String> metricNameByEntity, boolean refreshPending) {
    Map<String, DirectChildCountDTO> result = new LinkedHashMap<>();
    metricNameByEntity.forEach(
        (entity, ignored) ->
            result.put(
                entity,
                new DirectChildCountDTO(
                    null, DirectChildCountState.UNAVAILABLE, null, refreshPending)));
    return result;
  }

  private NameIdentifier[] listVisibleCatalogIdentifiers(String metalake) {
    NameIdentifier[] catalogIdents = catalogDispatcher.listCatalogs(Namespace.of(metalake));
    return filterByNameIdentifier(
        metalake,
        AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.CATALOG,
        catalogIdents);
  }

  private NameIdentifier[] listVisibleSchemaIdentifiers(Namespace namespace) {
    return MetadataListingHelper.listVisibleSchemaIdentifiers(schemaDispatcher, namespace);
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

  private TagEntity[] filterTagsByAuth(String metalake, TagEntity[] tags) {
    if (tags.length == 0) {
      return tags;
    }
    return filterByExpression(
        metalake,
        AuthorizationExpressionConstants.LOAD_TAG_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.TAG,
        tags,
        tag -> NameIdentifierUtil.ofTag(metalake, tag.name()));
  }

  private PolicyEntity[] filterPoliciesByAuth(String metalake, PolicyEntity[] policies) {
    if (policies.length == 0) {
      return policies;
    }
    return filterByExpression(
        metalake,
        AuthorizationExpressionConstants.LOAD_POLICY_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.POLICY,
        policies,
        policy -> NameIdentifierUtil.ofPolicy(metalake, policy.name()));
  }

  private NameIdentifier[] filterByNameIdentifier(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      NameIdentifier[] identifiers) {
    return filterByExpression(metalake, expression, entityType, identifiers, id -> id);
  }

  private static class DirectChildCountReading {
    private final Long value;
    private final DirectChildCountState state;
    private final Long updatedAt;
    private final boolean needsRefresh;

    private DirectChildCountReading(
        Long value, DirectChildCountState state, Long updatedAt, boolean needsRefresh) {
      this.value = value;
      this.state = state;
      this.updatedAt = updatedAt;
      this.needsRefresh = needsRefresh;
    }

    private static DirectChildCountReading missing() {
      return new DirectChildCountReading(null, DirectChildCountState.UNAVAILABLE, null, true);
    }

    private DirectChildCountDTO toDTO(boolean refreshPending) {
      return new DirectChildCountDTO(value, state, updatedAt, refreshPending);
    }
  }
}
