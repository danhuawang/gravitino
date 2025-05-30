/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.dto.responses.DataPreviewResponse;
import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import com.google.common.collect.Sets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.NameIdentifierUtil;

@Path("/web/preview/metalakes/{metalake}/catalogs/{catalog}/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DataPreviewOperations {

  private final DatastratoTableDispatcher tableDispatcher;
  private final CatalogDispatcher catalogDispatcher;

  @Context private HttpServletRequest httpRequest;

  // Code represents the result of the data preview
  private final int PREVIEW_SUCCESS = 0;
  private final int PREVIEW_SENSITIVE_TABLE = 1;
  private final int CATALOG_IS_REGISTERING = 2;
  private final int CATALOG_PROVIDER_IS_UNSUPPORTED = 3;

  @Inject
  public DataPreviewOperations(
      TableDispatcher tableDispatcher, CatalogDispatcher catalogDispatcher) {
    this.tableDispatcher = (DatastratoTableDispatcher) tableDispatcher;
    this.catalogDispatcher = catalogDispatcher;
  }

  @GET
  @Path("schemas/{schema}/tables/{table}")
  @Produces("application/vnd.gravitino.v1+json")
  public Response previewTable(
      @PathParam("metalake") String metalake,
      @PathParam("catalog") String catalog,
      @PathParam("schema") String schema,
      @PathParam("table") String table,
      @QueryParam("resultLimit") @DefaultValue("100") int resultLimit) {
    NameIdentifier identifier = NameIdentifier.of(metalake, catalog, schema, table);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Column[] columns = tableDispatcher.loadTable(identifier).columns();

            Map<String, Object>[] results =
                tableDispatcher.preview(identifier, Entity.EntityType.TABLE, resultLimit);

            return Utils.ok(
                new DataPreviewResponse(
                    PREVIEW_SUCCESS,
                    "Succeed to preview data",
                    DTOConverters.toDTOs(columns),
                    results));
          });
    } catch (DataPreviewSensitiveTableException pe) {
      return Utils.ok(
          new DataPreviewResponse(PREVIEW_SENSITIVE_TABLE, pe.getMessage(), null, null));
    } catch (NotFoundException nfe) {
      return Utils.notFound(String.format("Table %s is not found", identifier), nfe);
    } catch (Exception e) {
      if (e instanceof RuntimeException
          && e.getCause() instanceof SQLException
          && e.getCause()
              .getMessage()
              .contains(
                  String.format(
                      "Catalog '\"%s\"' does not exist",
                      NameIdentifierUtil.getCatalogIdentifier(identifier)))) {
        Catalog catalogInfo =
            catalogDispatcher.loadCatalog(NameIdentifierUtil.getCatalogIdentifier(identifier));
        Set<String> supportedProviders =
            Sets.newHashSet("hive", "lakehouse-iceberg", "jdbc-mysql", "jdbc-postgresql");

        if (supportedProviders.contains(catalogInfo.provider())) {
          return Utils.ok(
              new DataPreviewResponse(
                  CATALOG_IS_REGISTERING,
                  String.format(
                      "Catalog %s is registering, wait for several seconds and retry",
                      NameIdentifierUtil.getCatalogIdentifier(identifier).name()),
                  null,
                  null));
        } else {
          return Utils.ok(
              new DataPreviewResponse(
                  CATALOG_PROVIDER_IS_UNSUPPORTED,
                  String.format(
                      "The provider of catalog %s is not supported for preview",
                      NameIdentifierUtil.getCatalogIdentifier(identifier).name()),
                  null,
                  null));
        }

      } else {
        return Utils.internalError("Error while preview table", e);
      }
    }
  }
}
