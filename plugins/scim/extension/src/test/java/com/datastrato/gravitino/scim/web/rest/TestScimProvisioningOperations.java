/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.dto.responses.ScimProvisioningListResponse;
import com.datastrato.gravitino.scim.model.ScimProvisioningSummary;
import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TestScimProvisioningOperations extends JerseyTest {

  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final ScimTokenManager TOKEN_MANAGER = mock(ScimTokenManager.class);
  private static final MetalakeDispatcher METALAKE_DISPATCHER = mock(MetalakeDispatcher.class);

  @BeforeEach
  void resetMocks() {
    reset(TOKEN_MANAGER, METALAKE_DISPATCHER);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 4000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteUser()).thenReturn(null);

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(ScimProvisioningOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(TOKEN_MANAGER).to(ScimTokenManager.class);
            bind(METALAKE_DISPATCHER).to(MetalakeDispatcher.class);
            bind(request).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  void testList() {
    when(METALAKE_DISPATCHER.listMetalakes()).thenReturn(new Metalake[0]);
    when(TOKEN_MANAGER.listProvisioningSummaries(any()))
        .thenReturn(List.of(row("acme", 1L, 1000L), row("sandbox", 0L, 0L)));

    try (MockedStatic<MetadataAuthzHelper> authz = mockStatic(MetadataAuthzHelper.class)) {
      authz
          .when(() -> MetadataAuthzHelper.filterMetalakes(any(), anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));

      ScimProvisioningListResponse body =
          target("/scim/provisioning")
              .request()
              .accept(ACCEPT)
              .get()
              .readEntity(ScimProvisioningListResponse.class);
      Assertions.assertEquals(2, body.getMetalakes().size());
      Assertions.assertEquals("acme", body.getMetalakes().get(0).getMetalake());
      Assertions.assertEquals(1L, body.getMetalakes().get(0).getTokenCount());
      Assertions.assertEquals(0L, body.getMetalakes().get(1).getLastUsedAt());
    }
  }

  private static ScimProvisioningSummary row(String metalake, long count, long lastUsed) {
    return ScimProvisioningSummary.builder()
        .withMetalake(metalake)
        .withEndpoint(ScimUtils.metalakeBasePath(metalake))
        .withTokenCount(count)
        .withLastUsedAt(lastUsed)
        .build();
  }
}
