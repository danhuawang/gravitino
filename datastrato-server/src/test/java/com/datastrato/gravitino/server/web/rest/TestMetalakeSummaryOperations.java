/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.responses.MetalakeSummaryResponse;
import com.datastrato.gravitino.metalake.MetalakeSummaryCounts;
import com.datastrato.gravitino.metalake.MetalakeSummaryMetaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Tests the metalake summary REST endpoint. */
public class TestMetalakeSummaryOperations extends JerseyTest {

  private static final String METALAKE = "metalake1";
  private static final MetadataObject METALAKE_OBJECT =
      MetadataObjects.of(null, METALAKE, MetadataObject.Type.METALAKE);
  private static final OwnerDispatcher ownerDispatcher = mock(OwnerDispatcher.class);

  private static final AccessControlDispatcher accessControlDispatcher =
      mock(AccessControlDispatcher.class);
  private static final MetalakeSummaryMetaService summaryMetaService =
      mock(MetalakeSummaryMetaService.class);

  private static final OwnerDispatcher publicOwnerDispatcher = mock(OwnerDispatcher.class);

  private static OwnerDispatcher previousOwnerDispatcher;
  private static OwnerDispatcher previousPublicOwnerDispatcher;

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
    previousPublicOwnerDispatcher = GravitinoEnv.getInstance().ownerDispatcher();
    previousOwnerDispatcher = GravitinoEnv.getInstance().internalOwnerDispatcher();
    Config config = mock(Config.class);
    Mockito.doReturn(false).when(config).get(ENABLE_AUTHORIZATION);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  /** Restores the owner dispatcher used before this test class. */
  @AfterAll
  public static void restoreOwnerDispatcher() throws IllegalAccessException {
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "ownerDispatcher", previousPublicOwnerDispatcher, true);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalOwnerDispatcher", previousOwnerDispatcher, true);
  }

  /** Resets dispatcher mocks before each test. */
  @BeforeEach
  public void resetMocks() throws IllegalAccessException {
    Mockito.reset(
        accessControlDispatcher, summaryMetaService, ownerDispatcher, publicOwnerDispatcher);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "ownerDispatcher", publicOwnerDispatcher, true);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalOwnerDispatcher", ownerDispatcher, true);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "accessControlDispatcher", accessControlDispatcher, true);
  }

  /** Verifies summary enrichment never calls the event-emitting owner dispatcher. */
  @AfterEach
  public void verifyNoPublicOwnerLookups() {
    Mockito.verifyNoInteractions(publicOwnerDispatcher);
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
    resourceConfig.register(ObjectMapperProvider.class);
    resourceConfig.register(new MetalakeSummaryOperations(summaryMetaService));
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
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(2L, 3L, 1L));

    MetalakeSummaryResponse summary = getSummary(Response.Status.OK.getStatusCode());
    Assertions.assertEquals(0, summary.getCode());
    Assertions.assertEquals(2, summary.getCatalogCount());
    Assertions.assertEquals(3L, summary.getUserCount());
    Assertions.assertEquals(1L, summary.getRoleCount());
    verify(summaryMetaService).loadCounts(METALAKE);
  }

  /** Tests that an empty metalake returns zero counts. */
  @Test
  public void testEmptyMetalakeReportsZeroes() {
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(0L, 0L, 0L));

    MetalakeSummaryResponse summary = getSummary(Response.Status.OK.getStatusCode());
    Assertions.assertEquals(0, summary.getCatalogCount());
    Assertions.assertEquals(0L, summary.getUserCount());
    Assertions.assertEquals(0L, summary.getRoleCount());
  }

  /** Tests that access control counts are absent when access control is disabled. */
  @Test
  public void testUserAndRoleCountsAreAbsentWithoutAccessControl() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "accessControlDispatcher", null, true);
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(1L, 2L, 3L));

    MetalakeSummaryResponse summary = getSummary(Response.Status.OK.getStatusCode());
    Assertions.assertEquals(1, summary.getCatalogCount());
    Assertions.assertNull(summary.getUserCount());
    Assertions.assertNull(summary.getRoleCount());
  }

  /** Tests that a missing metalake returns HTTP 404. */
  @Test
  public void testMissingMetalakeIsReported() {
    when(summaryMetaService.loadCounts(METALAKE))
        .thenThrow(new NoSuchMetalakeException("metalake does not exist"));

    Response response = request();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, error.getCode());
    Mockito.verifyNoInteractions(ownerDispatcher);
  }

  /** Tests that a failed persisted query returns an error instead of zero. */
  @Test
  public void testFailedCountIsNotReportedAsZero() {
    when(summaryMetaService.loadCounts(METALAKE))
        .thenThrow(new RuntimeException("summary count is unavailable"));

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

  @Test
  void testSummaryJsonIncludesUserOwner() throws IOException {
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(2L, 3L, 1L));
    stubOwner("alice", Owner.Type.USER);
    assertSummaryJson(
        """
        {"code":0,"catalogCount":2,"userCount":3,"roleCount":1,
         "owner":{"name":"alice","type":"user"}}
        """);
    verify(ownerDispatcher).getOwner(METALAKE, METALAKE_OBJECT);
  }

  @Test
  void testSummaryJsonIncludesGroupOwner() throws IOException {
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(2L, 3L, 1L));
    stubOwner("data-admins", Owner.Type.GROUP);
    assertSummaryJson(
        """
        {"code":0,"catalogCount":2,"userCount":3,"roleCount":1,
         "owner":{"name":"data-admins","type":"group"}}
        """);
  }

  @Test
  void testSummaryJsonIncludesExplicitNullForUnassignedOwner() throws IOException {
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(2L, 3L, 1L));
    when(ownerDispatcher.getOwner(METALAKE, METALAKE_OBJECT)).thenReturn(Optional.empty());
    assertSummaryJson(
        """
        {"code":0,"catalogCount":2,"userCount":3,"roleCount":1,"owner":null}
        """);
    verify(ownerDispatcher).getOwner(METALAKE, METALAKE_OBJECT);
  }

  @Test
  void testSummaryJsonWithoutAccessControlDoesNotLookUpOwner() throws Exception {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "accessControlDispatcher", null, true);
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(2L, 3L, 1L));
    assertSummaryJson("{\"code\":0,\"catalogCount\":2,\"owner\":null}");
    Mockito.verifyNoInteractions(ownerDispatcher);
  }

  @Test
  void testOwnerLookupFailureIsNotReportedAsUnassigned() throws IOException {
    when(summaryMetaService.loadCounts(METALAKE)).thenReturn(new MetalakeSummaryCounts(2L, 3L, 1L));
    when(ownerDispatcher.getOwner(METALAKE, METALAKE_OBJECT))
        .thenThrow(new IllegalStateException("Owner storage unavailable"));
    try (Response response = request()) {
      Assertions.assertEquals(500, response.getStatus());
      String body = response.readEntity(String.class);
      Assertions.assertEquals(1002, new ObjectMapper().readTree(body).get("code").intValue());
      Assertions.assertFalse(new ObjectMapper().readTree(body).has("owner"));
    }
  }

  private void stubOwner(String name, Owner.Type type) {
    Owner owner = mock(Owner.class);
    when(owner.name()).thenReturn(name);
    when(owner.type()).thenReturn(type);
    when(ownerDispatcher.getOwner(METALAKE, METALAKE_OBJECT)).thenReturn(Optional.of(owner));
  }

  private void assertSummaryJson(String expectedJson) throws IOException {
    try (Response response = request()) {
      Assertions.assertEquals(200, response.getStatus());
      Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
      String actualJson = response.readEntity(String.class);
      ObjectMapper parser = new ObjectMapper();
      Assertions.assertEquals(
          parser.readTree(expectedJson), parser.readTree(actualJson), actualJson);
    }
  }

  /** Calls the endpoint and reads a successful summary. */
  private MetalakeSummaryResponse getSummary(int expectedStatus) {
    Response response = request();
    Assertions.assertEquals(expectedStatus, response.getStatus());
    return response.readEntity(MetalakeSummaryResponse.class);
  }

  /** Calls the metalake summary endpoint. */
  private Response request() {
    return target("/internal/metalakes/" + METALAKE + "/summary")
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .get();
  }
}
