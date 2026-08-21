/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.responses.ExtendedPolicyListResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
import org.apache.gravitino.Namespace;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.storage.relational.service.DatastratoPolicyMetaService;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestExtendedPolicyOperations extends JerseyTest {

  private static final PolicyDispatcher policyDispatcher = mock(PolicyDispatcher.class);
  private static final DatastratoPolicyMetaService policyMetaService =
      mock(DatastratoPolicyMetaService.class);

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "policyDispatcher", policyDispatcher, true);
  }

  @BeforeEach
  public void resetMocks() {
    reset(policyDispatcher, policyMetaService);
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
    resourceConfig.register(new ExtendedPolicyOperations(policyDispatcher, policyMetaService));
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testListPolicyNames() {
    when(policyDispatcher.listPolicies("testMetalake")).thenReturn(new String[] {"p1", "p2"});

    Response resp =
        target("/web/metalakes/testMetalake/policies")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    NameListResponse response = resp.readEntity(NameListResponse.class);
    Assertions.assertEquals(0, response.getCode());
    Assertions.assertArrayEquals(new String[] {"p1", "p2"}, response.getNames());
  }

  @Test
  public void testListPolicyInfosWithAssociatedCounts() throws Exception {
    PolicyEntity p1 = buildPolicyEntity(1L, "p1");
    PolicyEntity p2 = buildPolicyEntity(2L, "p2");

    when(policyDispatcher.listPolicyInfos("testMetalake")).thenReturn(new PolicyEntity[] {p1, p2});
    when(policyMetaService.listAssociatedMetadataObjectsForPolicies(
            "testMetalake", List.of(1L, 2L)))
        .thenReturn(
            ImmutableMap.of(
                1L,
                List.of(tableObject("table1"), tableObject("table2"), tableObject("table3")),
                2L,
                List.of()));

    Response resp =
        target("/web/metalakes/testMetalake/policies")
            .queryParam("details", "true")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    String entityJson = resp.readEntity(String.class);
    Assertions.assertTrue(entityJson.contains("\"code\":0"));
    Assertions.assertTrue(entityJson.contains("\"name\":\"p1\""));
    Assertions.assertTrue(entityJson.contains("\"associatedObjectsCount\":3"));
    Assertions.assertTrue(entityJson.contains("\"name\":\"p2\""));
    Assertions.assertTrue(entityJson.contains("\"associatedObjectsCount\":0"));
  }

  @Test
  public void testListPolicyInfosFiltersBeforeCountingVisibleObjects() throws Exception {
    PolicyEntity visiblePolicy = buildPolicyEntity(1L, "visiblePolicy");
    PolicyEntity hiddenPolicy = buildPolicyEntity(2L, "hiddenPolicy");
    MetadataObject visibleObject = tableObject("visibleTable");
    MetadataObject hiddenObject = tableObject("hiddenTable");

    when(policyDispatcher.listPolicyInfos("testMetalake"))
        .thenReturn(new PolicyEntity[] {visiblePolicy, hiddenPolicy});
    when(policyMetaService.listAssociatedMetadataObjectsForPolicies("testMetalake", List.of(1L)))
        .thenReturn(ImmutableMap.of(1L, List.of(visibleObject, hiddenObject)));

    try (MockedStatic<MetadataAuthzHelper> metadataAuthzHelper =
        Mockito.mockStatic(MetadataAuthzHelper.class)) {
      metadataAuthzHelper
          .when(
              () ->
                  MetadataAuthzHelper.filterByExpression(
                      Mockito.eq("testMetalake"),
                      Mockito.eq(
                          AuthorizationExpressionConstants.LOAD_POLICY_AUTHORIZATION_EXPRESSION),
                      Mockito.eq(Entity.EntityType.POLICY),
                      Mockito.any(PolicyEntity[].class),
                      Mockito.any()))
          .thenReturn(new PolicyEntity[] {visiblePolicy});
      metadataAuthzHelper
          .when(
              () ->
                  MetadataAuthzHelper.filterMetadataObject(
                      Mockito.eq("testMetalake"), Mockito.any(MetadataObject[].class)))
          .thenAnswer(
              invocation -> {
                MetadataObject[] objects = invocation.getArgument(1);
                return Arrays.stream(objects)
                    .filter(object -> object.name().equals("visibleTable"))
                    .toArray(MetadataObject[]::new);
              });

      ExtendedPolicyOperations operations =
          new ExtendedPolicyOperations(policyDispatcher, policyMetaService);
      FieldUtils.writeField(operations, "httpRequest", mock(HttpServletRequest.class), true);

      Response response = operations.listPolicies("testMetalake", true);

      Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
      ExtendedPolicyListResponse listResponse = (ExtendedPolicyListResponse) response.getEntity();
      Assertions.assertEquals(1, listResponse.getPolicies().length);
      Assertions.assertEquals("visiblePolicy", listResponse.getPolicies()[0].getPolicy().name());
      Assertions.assertEquals(1, listResponse.getPolicies()[0].getAssociatedObjectsCount());
      Mockito.verify(policyMetaService)
          .listAssociatedMetadataObjectsForPolicies("testMetalake", List.of(1L));
    }
  }

  @Test
  public void testListPoliciesNoSuchMetalake() {
    when(policyDispatcher.listPolicies("testMetalake"))
        .thenThrow(new NoSuchMetalakeException("Metalake testMetalake does not exist"));

    Response resp =
        target("/web/metalakes/testMetalake/policies")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp.getStatus());
    ErrorResponse errorResponse = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchMetalakeException.class.getSimpleName(), errorResponse.getType());
  }

  private PolicyEntity buildPolicyEntity(Long id, String name) {
    ImmutableMap<String, Object> contentFields = ImmutableMap.of("k", "v");
    PolicyContent content =
        PolicyContents.custom(contentFields, ImmutableSet.of(MetadataObject.Type.TABLE), null);
    return PolicyEntity.builder()
        .withId(id)
        .withName(name)
        .withNamespace(Namespace.of("testMetalake"))
        .withPolicyType(Policy.BuiltInType.CUSTOM)
        .withComment("comment")
        .withEnabled(true)
        .withContent(content)
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private MetadataObject tableObject(String name) {
    return MetadataObjects.parse("catalog.schema." + name, MetadataObject.Type.TABLE);
  }
}
