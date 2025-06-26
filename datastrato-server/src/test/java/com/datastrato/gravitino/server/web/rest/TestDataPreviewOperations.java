/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.dto.responses.DataPreviewResponse;
import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestDataPreviewOperations extends JerseyTest {
  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private final DatastratoTableDispatcher tableDispatcher = mock(DatastratoTableDispatcher.class);
  private final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(DataPreviewOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(tableDispatcher).to(TableDispatcher.class).ranked(2);
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testPreviewTable() {
    Table table = mock(Table.class);
    when(tableDispatcher.loadTable(any())).thenReturn(table);
    when(tableDispatcher.preview(any(), any(), anyInt(), any())).thenReturn(new Map[0]);
    when(table.columns()).thenReturn(new Column[0]);
    Response resp =
        target("/web/preview/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables/table1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    DataPreviewResponse dataPreviewResponse = resp.readEntity(DataPreviewResponse.class);
    Assertions.assertEquals(0, dataPreviewResponse.getCode());
    Assertions.assertEquals("Succeed to preview data", dataPreviewResponse.getMessage());

    // test to preview the sensitive table
    doThrow(new DataPreviewSensitiveTableException("mock error"))
        .when(tableDispatcher)
        .preview(any(), any(), anyInt(), any());
    resp =
        target("/web/preview/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables/table1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    dataPreviewResponse = resp.readEntity(DataPreviewResponse.class);
    Assertions.assertEquals(1, dataPreviewResponse.getStatusCode());
    Assertions.assertEquals("mock error", dataPreviewResponse.getMessage());

    // test to preview not-existed table
    doThrow(new NoSuchTableException("mock error"))
        .when(tableDispatcher)
        .preview(any(), any(), anyInt(), any());
    Response errorResp =
        target("/web/preview/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables/table1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(NOT_FOUND.getStatusCode(), errorResp.getStatus());

    ErrorResponse errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchTableException.class.getSimpleName(), errorResponse.getType());

    // test with runtime exception
    doThrow(new RuntimeException("mock error"))
        .when(tableDispatcher)
        .preview(any(), any(), anyInt(), any());
    errorResp =
        target("/web/preview/metalakes/metalake1/catalogs/catalog1/schemas/schema1/tables/table1")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), errorResp.getStatus());

    errorResponse = errorResp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse.getCode());
    Assertions.assertEquals(RuntimeException.class.getSimpleName(), errorResponse.getType());
  }
}
