/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.credential.CredentialContext;
import org.apache.gravitino.credential.CredentialProvider;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class TestCredentialProviderOperations extends JerseyTest {

  private static volatile CredentialProvider testProvider;
  private static volatile String testCredentialType;
  private static volatile Map<String, String> testProperties;

  private static class MockCredentialProviderOperations extends CredentialProviderOperations {
    @Override
    CredentialProvider createCredentialProvider(
        String credentialType, Map<String, String> properties) {
      testCredentialType = credentialType;
      testProperties = properties;
      return testProvider;
    }
  }

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      return mock(HttpServletRequest.class);
    }
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
    resourceConfig.register(MockCredentialProviderOperations.class);
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
  public void testCredentialProvider() throws IOException {
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.of(mock(Credential.class)));

    testProvider = provider;

    Response response =
        target("/web/credential-providers/test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.json(
                    "{\"path\":\"s3://bucket/path\"," + "\"properties\":{\"key\":\"value\"}}"));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(0, response.readEntity(BaseResponse.class).getCode());
    Assertions.assertEquals("test-provider", testCredentialType);
    Assertions.assertEquals(Collections.singletonMap("key", "value"), testProperties);

    ArgumentCaptor<CredentialContext> contextCaptor =
        ArgumentCaptor.forClass(CredentialContext.class);
    verify(provider).getCredentialOptional(contextCaptor.capture());
    Assertions.assertInstanceOf(PathBasedCredentialContext.class, contextCaptor.getValue());
    PathBasedCredentialContext context = (PathBasedCredentialContext) contextCaptor.getValue();
    Assertions.assertEquals(Collections.singleton("s3://bucket/path"), context.getReadPaths());
    Assertions.assertEquals(Collections.singleton("s3://bucket/path"), context.getWritePaths());
    verify(provider).close();
  }

  @Test
  public void testInvalidRequest() {
    assertInvalidRequest("{}");
    assertInvalidRequest("{\"properties\":{}}");
    assertInvalidRequest("{\"path\":\"  \",\"properties\":{}}");
    assertInvalidRequest("{\"path\":\"s3://bucket/path\"}");
  }

  @Test
  public void testCredentialProviderFailure() throws IOException {
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.empty());

    testProvider = provider;

    Response response =
        target("/web/credential-providers/test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\",\"properties\":{}}"));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.INTERNAL_ERROR_CODE, errorResponse.getCode());
    verify(provider).close();
  }

  private void assertInvalidRequest(String requestBody) {
    Response response =
        target("/web/credential-providers/test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json(requestBody));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResponse.getCode());
  }
}
