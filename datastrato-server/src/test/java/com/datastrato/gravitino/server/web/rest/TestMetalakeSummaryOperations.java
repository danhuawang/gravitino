/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.responses.MetalakeSummaryResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Tests the metalake summary REST endpoint. */
public class TestMetalakeSummaryOperations extends JerseyTest {

  private static final String METALAKE = "metalake1";

  private static final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);
  private static final AccessControlDispatcher accessControlDispatcher =
      mock(AccessControlDispatcher.class);

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    /** {@inheritDoc} */
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  /** Sets the shared Gravitino environment used by the endpoint. */
  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "catalogDispatcher", catalogDispatcher, true);
  }

  /** Resets dispatcher mocks before each test. */
  @BeforeEach
  public void resetMocks() throws IllegalAccessException {
    Mockito.reset(catalogDispatcher, accessControlDispatcher);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "accessControlDispatcher", accessControlDispatcher, true);
  }

  /** {@inheritDoc} */
  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(MetalakeSummaryOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          /** {@inheritDoc} */
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Tests a summary with catalogs, users, and roles. */
  @Test
  public void testGetSummary() {
    when(catalogDispatcher.listCatalogs(Namespace.of(METALAKE)))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(METALAKE, "catalog1"), NameIdentifier.of(METALAKE, "catalog2")
            });
    when(accessControlDispatcher.countUsers(METALAKE)).thenReturn(3L);
    when(accessControlDispatcher.listRoleNames(METALAKE)).thenReturn(new String[] {"role1"});

    MetalakeSummaryResponse summary = getSummary(Response.Status.OK.getStatusCode());
    Assertions.assertEquals(0, summary.getCode());
    Assertions.assertEquals(2, summary.getCatalogCount());
    Assertions.assertEquals(3L, summary.getUserCount());
    Assertions.assertEquals(1L, summary.getRoleCount());
  }

  /** Tests that an empty metalake returns zero counts. */
  @Test
  public void testEmptyMetalakeReportsZeroes() {
    when(catalogDispatcher.listCatalogs(Namespace.of(METALAKE))).thenReturn(new NameIdentifier[0]);
    when(accessControlDispatcher.countUsers(METALAKE)).thenReturn(0L);
    when(accessControlDispatcher.listRoleNames(METALAKE)).thenReturn(new String[0]);

    MetalakeSummaryResponse summary = getSummary(Response.Status.OK.getStatusCode());
    Assertions.assertEquals(0, summary.getCatalogCount());
    Assertions.assertEquals(0L, summary.getUserCount());
    Assertions.assertEquals(0L, summary.getRoleCount());
  }

  /** Tests that access control counts are absent when access control is disabled. */
  @Test
  public void testUserAndRoleCountsAreAbsentWithoutAccessControl() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "accessControlDispatcher", null, true);
    when(catalogDispatcher.listCatalogs(Namespace.of(METALAKE)))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(METALAKE, "catalog1")});

    MetalakeSummaryResponse summary = getSummary(Response.Status.OK.getStatusCode());
    Assertions.assertEquals(1, summary.getCatalogCount());
    Assertions.assertNull(summary.getUserCount());
    Assertions.assertNull(summary.getRoleCount());
  }

  /** Tests that a missing metalake returns HTTP 404. */
  @Test
  public void testMissingMetalakeIsReported() {
    when(catalogDispatcher.listCatalogs(Namespace.of(METALAKE)))
        .thenThrow(new NoSuchMetalakeException("metalake does not exist"));

    Response response = request();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, error.getCode());
  }

  /** Tests that a failed count returns an error instead of zero. */
  @Test
  public void testFailedCountIsNotReportedAsZero() {
    // A summary must not claim the metalake is empty when the listing failed.
    when(catalogDispatcher.listCatalogs(Namespace.of(METALAKE)))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(METALAKE, "catalog1")});
    when(accessControlDispatcher.countUsers(METALAKE))
        .thenThrow(new RuntimeException("user count is unavailable"));

    Response response = request();
    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
  }

  /** Tests the authorization metadata that protects the endpoint. */
  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    Method method = MetalakeSummaryOperations.class.getMethod("getSummary", String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals("METALAKE::OWNER", expression.expression());

    Parameter metalakeParameter = method.getParameters()[0];
    AuthorizationMetadata metadata = metalakeParameter.getAnnotation(AuthorizationMetadata.class);
    Assertions.assertNotNull(metadata);
    Assertions.assertEquals(Entity.EntityType.METALAKE, metadata.type());
  }

  /** Calls the endpoint and reads a successful summary. */
  private MetalakeSummaryResponse getSummary(int expectedStatus) {
    Response response = request();
    Assertions.assertEquals(expectedStatus, response.getStatus());
    return response.readEntity(MetalakeSummaryResponse.class);
  }

  /** Calls the metalake summary endpoint. */
  private Response request() {
    return target("/web/metalakes/" + METALAKE + "/summary")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .get();
  }
}
