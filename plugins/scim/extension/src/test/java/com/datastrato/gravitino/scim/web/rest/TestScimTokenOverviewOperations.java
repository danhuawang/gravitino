/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenOverviewResponse;
import com.datastrato.gravitino.scim.model.ScimTokenOverview;
import com.datastrato.gravitino.scim.model.ScimTokenSummary;
import java.io.IOException;
import java.util.List;
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

class TestScimTokenOverviewOperations extends JerseyTest {

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
    resourceConfig.register(ScimTokenOverviewOperations.class);
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
  void testOverview() {
    List<ScimTokenSummary> rows =
        List.of(
            summary("entra", 0L, "valid", 500L, 900L), summary("old-okta", 0L, "valid", 600L, 0L));
    doReturn(ScimTokenOverview.of(900L, rows)).when(TOKEN_MANAGER).getScimTokenOverview();

    ScimTokenOverviewResponse body =
        target("/scim/tokens/overview")
            .request()
            .accept(ACCEPT)
            .get()
            .readEntity(ScimTokenOverviewResponse.class);
    Assertions.assertEquals(900L, body.getLastUsedAt());
    Assertions.assertEquals(2L, body.getTokenCount());
    Assertions.assertEquals(2, body.getTokens().size());
    Assertions.assertEquals("entra", body.getTokens().get(0).getTokenName());
    Assertions.assertEquals(500L, body.getTokens().get(0).getCreatedAt());
  }

  private static ScimTokenSummary summary(
      String name, long expires, String status, long created, long used) {
    return ScimTokenSummary.builder()
        .withTokenName(name)
        .withExpiresAt(expires)
        .withStatus(status)
        .withCreatedAt(created)
        .withLastUsedAt(used)
        .build();
  }
}
