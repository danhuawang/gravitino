/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.dto.rel.ExtendedColumnDTO;
import com.datastrato.gravitino.dto.rel.TableWithColumnTagsDTO;
import com.datastrato.gravitino.dto.responses.TableWithColumnTagsResponse;
import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.annotations.AuthorizationRequest;
import org.apache.gravitino.server.authorization.annotations.ExpressionCondition;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** REST operations that extend table responses for the Enterprise Web UI. */
@Path("web/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables")
public class ExtendedTableOperations {

  private static final Logger LOG = LoggerFactory.getLogger(ExtendedTableOperations.class);

  private final TableDispatcher tableDispatcher;
  private final ColumnTagResolver columnTagResolver;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates Enterprise table operations.
   *
   * @param tableDispatcher The table dispatcher.
   */
  @Inject
  public ExtendedTableOperations(TableDispatcher tableDispatcher) {
    this(tableDispatcher, new ColumnTagResolver());
  }

  @VisibleForTesting
  ExtendedTableOperations(TableDispatcher tableDispatcher, ColumnTagResolver columnTagResolver) {
    this.tableDispatcher = Objects.requireNonNull(tableDispatcher);
    this.columnTagResolver = Objects.requireNonNull(columnTagResolver);
  }

  /**
   * Loads a table whose columns include visible effective tags.
   *
   * @param metalake The metalake name.
   * @param catalog The catalog name.
   * @param schema The schema name.
   * @param table The table name.
   * @param requiredPrivileges Optional privileges required by the caller.
   * @return The loaded table with tags on every column.
   */
  @GET
  @Path("{table}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-load-table." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-load-table", absolute = true)
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
      secondaryExpression = AuthorizationExpressionConstants.MODIFY_TABLE_AUTHORIZATION_EXPRESSION,
      secondaryExpressionCondition = ExpressionCondition.REQUIRED_MODIFY_PRIVILEGES,
      accessMetadataType = MetadataObject.Type.TABLE)
  public Response loadTable(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("catalog") @AuthorizationMetadata(type = Entity.EntityType.CATALOG) String catalog,
      @PathParam("schema") @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) String schema,
      @PathParam("table") @AuthorizationMetadata(type = Entity.EntityType.TABLE) String table,
      @QueryParam("privileges")
          @AuthorizationRequest(type = AuthorizationRequest.RequestType.LOAD_TABLE)
          String requiredPrivileges) {
    LOG.info(
        "Received Enterprise load table request for table: {}.{}.{}.{}",
        metalake,
        catalog,
        schema,
        table);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier =
                NameIdentifierUtil.ofTable(metalake, catalog, schema, table);
            Table loadedTable = tableDispatcher.loadTable(identifier);
            TableDTO tableDTO = DTOConverters.toDTO(loadedTable);
            Map<String, TagDTO[]> tagsByColumn =
                columnTagResolver.resolve(
                    metalake,
                    catalog,
                    schema,
                    table,
                    loadedTable,
                    tags -> filterTagsByAuthorization(metalake, tags));
            ExtendedColumnDTO[] columns =
                Arrays.stream(tableDTO.columns())
                    .map(
                        column ->
                            new ExtendedColumnDTO(
                                DTOConverters.toDTO(column),
                                tagsByColumn.getOrDefault(column.name(), new TagDTO[0])))
                    .toArray(ExtendedColumnDTO[]::new);
            TableWithColumnTagsResponse response =
                new TableWithColumnTagsResponse(new TableWithColumnTagsDTO(tableDTO, columns));
            LOG.info(
                "Enterprise table loaded with column tags: {}.{}.{}.{}",
                metalake,
                catalog,
                schema,
                table);
            return Utils.ok(response);
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleTableException(OperationType.LOAD, table, schema, e);
    }
  }

  private TagEntity[] filterTagsByAuthorization(String metalake, TagEntity[] tags) {
    if (tags.length == 0) {
      return tags;
    }
    return MetadataAuthzHelper.filterByExpression(
        metalake,
        AuthorizationExpressionConstants.LOAD_TAG_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.TAG,
        tags,
        tag -> NameIdentifierUtil.ofTag(metalake, tag.name()));
  }
}
