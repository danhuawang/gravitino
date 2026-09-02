/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.dto.responses.ScimProvisioningListResponse;
import com.datastrato.gravitino.scim.model.ScimProvisioningSummary;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimProvisioningOperations extends JerseyTest {

  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final ScimTokenManager TOKEN_MANAGER = mock(ScimTokenManager.class);

  @BeforeEach
  void resetMocks() {
    reset(TOKEN_MANAGER);
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
            bind(request).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  void testList() {
    when(TOKEN_MANAGER.getProvisioningSummary())
        .thenReturn(
            ScimProvisioningSummary.builder()
                .withEndpoint(ScimUtils.scimBasePath())
                .withTokenCount(2L)
                .withLastUsedAt(1000L)
                .build());

    ScimProvisioningListResponse body =
        target("/scim/provisioning")
            .request()
            .accept(ACCEPT)
            .get()
            .readEntity(ScimProvisioningListResponse.class);
    Assertions.assertEquals(1, body.getProvisioning().size());
    Assertions.assertEquals(ScimUtils.scimBasePath(), body.getProvisioning().get(0).getEndpoint());
    Assertions.assertEquals(2L, body.getProvisioning().get(0).getTokenCount());
    Assertions.assertEquals(1000L, body.getProvisioning().get(0).getLastUsedAt());
  }
}
