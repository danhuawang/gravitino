/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.connection.CatalogConnectionSnapshot;
import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.credential.CredentialContext;
import org.apache.gravitino.credential.CredentialProvider;
import org.apache.gravitino.credential.PathBasedCredentialContext;
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

  private final ConnectionTestStore connectionTestStore = mock(ConnectionTestStore.class);

  private static class MockCredentialProviderOperations extends CredentialProviderOperations {
    @Inject
    MockCredentialProviderOperations(ConnectionTestStore connectionTestStore) {
      super(connectionTestStore, Clock.fixed(Instant.ofEpochMilli(123456L), ZoneOffset.UTC));
    }

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
            bind(connectionTestStore).to(ConnectionTestStore.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Verifies a successful configured-provider probe is persisted and returns HTTP 200. */
  @Test
  public void testExistingCredentialProviderPersistsPassedResult() throws IOException {
    CatalogConnectionSnapshot snapshot =
        new CatalogConnectionSnapshot(
            10L,
            1L,
            "catalog",
            "fileset",
            Map.of("key", "saved-value", "credential-providers", "test-provider"));
    when(connectionTestStore.loadCatalogConnectionSnapshot(any())).thenReturn(snapshot);
    when(connectionTestStore.recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            anyLong(),
            eq(null)))
        .thenReturn(true);
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.of(mock(Credential.class)));
    testProvider = provider;

    Response response =
        target(
                "/web/metalakes/metalake/connections/catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(
        Map.of("key", "saved-value", "credential-providers", "test-provider"), testProperties);
    Assertions.assertEquals("test-provider", testCredentialType);
    ArgumentCaptor<CredentialContext> contextCaptor =
        ArgumentCaptor.forClass(CredentialContext.class);
    verify(provider).getCredentialOptional(contextCaptor.capture());
    Assertions.assertInstanceOf(PathBasedCredentialContext.class, contextCaptor.getValue());
    PathBasedCredentialContext context = (PathBasedCredentialContext) contextCaptor.getValue();
    Assertions.assertEquals(Collections.singleton("s3://bucket/path"), context.getReadPaths());
    Assertions.assertEquals(Collections.singleton("s3://bucket/path"), context.getWritePaths());
    verify(provider).close();
    verify(connectionTestStore)
        .recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            eq(123456L),
            eq(null));
  }

  /** Verifies a failed configured-provider probe persists a safe failure result. */
  @Test
  public void testExistingCredentialProviderPersistsFailedResult() throws IOException {
    CatalogConnectionSnapshot snapshot =
        new CatalogConnectionSnapshot(
            11L,
            1L,
            "failed-catalog",
            "fileset",
            Map.of("key", "saved-value", "credential-providers", "test-provider"));
    when(connectionTestStore.loadCatalogConnectionSnapshot(any())).thenReturn(snapshot);
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.empty());
    testProvider = provider;

    Response response =
        target(
                "/web/metalakes/metalake/connections/failed-catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(
        "Failed to test credential provider test-provider for connection metalake.failed-catalog",
        errorResponse.getMessage());
    Assertions.assertNull(errorResponse.getStack());
    verify(connectionTestStore)
        .recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.FAILED),
            eq(123456L),
            eq("Failed to test the credential provider"));
    verify(provider).close();
  }

  /** Verifies a passed probe does not persist a failure when result storage fails. */
  @Test
  public void testPassedProbeDoesNotPersistFailureWhenStorageFails() {
    CatalogConnectionSnapshot snapshot =
        new CatalogConnectionSnapshot(
            12L,
            1L,
            "storage-failure",
            "fileset",
            Map.of("key", "saved-value", "credential-providers", "test-provider"));
    when(connectionTestStore.loadCatalogConnectionSnapshot(any())).thenReturn(snapshot);
    when(connectionTestStore.recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            anyLong(),
            eq(null)))
        .thenThrow(new RuntimeException("storage unavailable"));
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.of(mock(Credential.class)));
    testProvider = provider;

    Response response =
        target(
                "/web/metalakes/metalake/connections/storage-failure/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertNull(errorResponse.getStack());
    verify(connectionTestStore, never())
        .recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.FAILED),
            anyLong(),
            any());
  }

  /** Verifies invalid requests and missing Catalogs produce client-facing errors. */
  @Test
  public void testExistingCredentialProviderValidationAndMissingCatalog() {
    String path =
        "/web/metalakes/metalake/connections/missing/credential-providers/" + "test-provider/test";

    Response invalid =
        target(path)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{}"));
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), invalid.getStatus());
    ErrorResponse invalidError = invalid.readEntity(ErrorResponse.class);
    Assertions.assertEquals("Invalid credential provider test request", invalidError.getMessage());
    Assertions.assertNull(invalidError.getStack());

    Response missing =
        target(path)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), missing.getStatus());
  }

  /** Verifies a canonical provider absent from the Catalog configuration cannot be tested. */
  @Test
  public void testUnconfiguredCredentialProviderIsRejected() {
    CatalogConnectionSnapshot snapshot =
        new CatalogConnectionSnapshot(
            13L,
            1L,
            "catalog",
            "fileset",
            Collections.singletonMap("credential-providers", "s3-token"));
    when(connectionTestStore.loadCatalogConnectionSnapshot(any())).thenReturn(snapshot);
    testCredentialType = null;

    Response response =
        target(
                "/web/metalakes/metalake/connections/catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    Assertions.assertNull(testCredentialType);
    verify(connectionTestStore, never()).recordTestResult(any(), any(), any(), anyLong(), any());
  }

  /** Verifies a stale or superseded result is not reported as successful. */
  @Test
  public void testRejectedPassedResultIsNotReportedAsSuccessful() {
    CatalogConnectionSnapshot snapshot =
        new CatalogConnectionSnapshot(
            14L,
            1L,
            "catalog",
            "fileset",
            Collections.singletonMap("credential-providers", "test-provider"));
    when(connectionTestStore.loadCatalogConnectionSnapshot(any())).thenReturn(snapshot);
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.of(mock(Credential.class)));
    testProvider = provider;
    when(connectionTestStore.recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            anyLong(),
            eq(null)))
        .thenReturn(false);

    Response response =
        target(
                "/web/metalakes/metalake/connections/catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(
        "Credential provider test result could not be recorded because the Catalog configuration "
            + "changed or a newer result already exists",
        errorResponse.getMessage());
    verify(connectionTestStore)
        .recordTestResult(
            eq(snapshot),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            eq(123456L),
            eq(null));
  }
}
