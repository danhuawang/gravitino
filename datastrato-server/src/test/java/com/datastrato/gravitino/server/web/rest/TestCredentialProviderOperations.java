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
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.credential.CredentialContext;
import org.apache.gravitino.credential.CredentialProvider;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.meta.CatalogEntity;
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
  private final CatalogDispatcher catalogDispatcher = mock(CatalogDispatcher.class);

  /** Simulates the overlapping connection root resource registered by the production server. */
  @Path("/web/metalakes/{metalake}/connections")
  public static class CompetingConnectionOperations {

    /**
     * Returns an empty connection response.
     *
     * @return An empty response.
     */
    @GET
    public Response listConnections() {
      return Response.ok().build();
    }
  }

  private static class MockCredentialProviderOperations extends CredentialProviderOperations {
    @Inject
    MockCredentialProviderOperations(
        CatalogDispatcher catalogDispatcher, ConnectionTestStore connectionTestStore) {
      super(
          catalogDispatcher,
          connectionTestStore,
          Clock.fixed(Instant.ofEpochMilli(123456L), ZoneOffset.UTC));
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
    resourceConfig.register(CompetingConnectionOperations.class);
    resourceConfig.register(MockCredentialProviderOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(connectionTestStore).to(ConnectionTestStore.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
          }
        });
    return resourceConfig;
  }

  private Catalog catalog(String name, Map<String, String> properties) {
    BaseCatalog<?> catalog = mock(BaseCatalog.class);
    CatalogEntity entity = mock(CatalogEntity.class);
    when(catalog.name()).thenReturn(name);
    when(catalog.provider()).thenReturn("fileset");
    when(catalog.propertiesWithCredentialProviders()).thenReturn(properties);
    when(catalog.entity()).thenReturn(entity);
    when(entity.getProperties()).thenReturn(properties);
    return catalog;
  }

  private Catalog genericCatalog(String name, Map<String, String> properties) {
    Catalog catalog = mock(Catalog.class);
    when(catalog.name()).thenReturn(name);
    when(catalog.provider()).thenReturn("fileset");
    when(catalog.properties()).thenReturn(properties);
    return catalog;
  }

  private void loadCatalog(String name, Map<String, String> properties) {
    loadCatalog(catalog(name, properties));
  }

  private void loadCatalog(Catalog loadedCatalog) {
    when(catalogDispatcher.loadCatalog(any())).thenReturn(loadedCatalog);
  }

  /** Verifies a successful configured-provider probe is persisted and returns HTTP 200. */
  @Test
  public void testExistingCredentialProviderPersistsPassedResult() throws IOException {
    Map<String, String> properties =
        Map.of("key", "saved-value", "credential-providers", "test-provider");
    loadCatalog(genericCatalog("catalog", properties));
    when(connectionTestStore.recordTestResult(
            eq(NameIdentifier.of("metalake", "catalog")),
            eq("fileset"),
            eq(properties),
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
            eq(NameIdentifier.of("metalake", "catalog")),
            eq("fileset"),
            eq(properties),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            eq(123456L),
            eq(null));
    verify(connectionTestStore, never()).getValidTestResult(any(), eq(ConnectionTestType.CATALOG));
    verify(connectionTestStore, never()).loadCatalogConnectionSnapshot(any());
  }

  /**
   * Verifies proposed property updates are applied in memory without changing the persisted test
   * status.
   */
  @Test
  public void testProposedCredentialUpdatesAreNotPersisted() throws IOException {
    Map<String, String> properties =
        Map.of(
            "credential-providers", "s3-token", "key", "saved-value", "remove-me", "saved-value");
    loadCatalog("catalog", properties);
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.of(mock(Credential.class)));
    testProvider = provider;

    Response response =
        target(
                "/web/metalakes/metalake/connections/catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.json(
                    "{\"path\":\"s3://bucket/path\",\"updates\":["
                        + "{\"@type\":\"setProperty\",\"property\":\"key\","
                        + "\"value\":\"proposed-value\"},"
                        + "{\"@type\":\"removeProperty\",\"property\":\"remove-me\"},"
                        + "{\"@type\":\"setProperty\",\"property\":\"credential-providers\","
                        + "\"value\":\"test-provider\"},"
                        + "{\"@type\":\"updateComment\",\"newComment\":\"ignored\"},"
                        + "{\"@type\":\"setSecretBinding\",\"property\":\"secret-key\","
                        + "\"provider\":\"vault\",\"plaintext\":\"proposed-secret\"}"
                        + "]}"));

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(
        Map.of(
            "credential-providers",
            "test-provider",
            "key",
            "proposed-value",
            "secret-key",
            "proposed-secret"),
        testProperties);
    verify(provider).close();
    verify(connectionTestStore, never())
        .recordTestResult(any(), any(), any(), any(), any(), anyLong(), any());
  }

  /** Verifies a failed probe with proposed updates does not replace the persisted test status. */
  @Test
  public void testFailedProposedCredentialUpdateIsNotPersisted() throws IOException {
    Map<String, String> properties =
        Map.of("credential-providers", "test-provider", "key", "saved-value");
    loadCatalog("catalog", properties);
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.empty());
    testProvider = provider;

    Response response =
        target(
                "/web/metalakes/metalake/connections/catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.json(
                    "{\"path\":\"s3://bucket/path\",\"updates\":["
                        + "{\"@type\":\"setProperty\",\"property\":\"key\","
                        + "\"value\":\"proposed-value\"}]}"));

    Assertions.assertEquals(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    verify(provider).close();
    verify(connectionTestStore, never())
        .recordTestResult(any(), any(), any(), any(), any(), anyLong(), any());
  }

  /** Verifies a failed configured-provider probe persists a safe failure result. */
  @Test
  public void testExistingCredentialProviderPersistsFailedResult() throws IOException {
    Map<String, String> properties =
        Map.of("key", "saved-value", "credential-providers", "test-provider");
    loadCatalog("failed-catalog", properties);
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
            eq(NameIdentifier.of("metalake", "failed-catalog")),
            eq("fileset"),
            eq(properties),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.FAILED),
            eq(123456L),
            eq("Failed to test the credential provider"));
    verify(provider).close();
  }

  /** Verifies a passed probe does not persist a failure when result storage fails. */
  @Test
  public void testPassedProbeDoesNotPersistFailureWhenStorageFails() {
    Map<String, String> properties =
        Map.of("key", "saved-value", "credential-providers", "test-provider");
    loadCatalog("storage-failure", properties);
    when(connectionTestStore.recordTestResult(
            eq(NameIdentifier.of("metalake", "storage-failure")),
            eq("fileset"),
            eq(properties),
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
            eq(NameIdentifier.of("metalake", "storage-failure")),
            eq("fileset"),
            eq(properties),
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

    when(catalogDispatcher.loadCatalog(any()))
        .thenThrow(new NoSuchCatalogException("Catalog does not exist"));
    Response missing =
        target(path)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), missing.getStatus());
  }

  /** Verifies malformed and unsupported proposed updates produce client-facing errors. */
  @Test
  public void testProposedCredentialUpdateValidation() {
    String path =
        "/web/metalakes/metalake/connections/catalog/credential-providers/" + "test-provider/test";

    Response malformed =
        target(path)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.json(
                    "{\"path\":\"s3://bucket/path\",\"updates\":["
                        + "{\"@type\":\"setProperty\",\"property\":\"key\"}]}"));
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), malformed.getStatus());

    Response unsupported =
        target(path)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(
                Entity.json(
                    "{\"path\":\"s3://bucket/path\",\"updates\":["
                        + "{\"@type\":\"setSecretReference\",\"property\":\"secret-key\","
                        + "\"provider\":\"vault\",\"attributes\":{\"path\":\"secret/path\"}}"
                        + "]}"));
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), unsupported.getStatus());
    verify(catalogDispatcher, never()).loadCatalog(any());
  }

  /** Verifies a canonical provider absent from the Catalog configuration cannot be tested. */
  @Test
  public void testUnconfiguredCredentialProviderIsRejected() {
    Map<String, String> properties = Collections.singletonMap("credential-providers", "s3-token");
    loadCatalog("catalog", properties);
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
    verify(connectionTestStore, never())
        .recordTestResult(any(), any(), any(), any(), any(), anyLong(), any());
  }

  /** Verifies null BaseCatalog properties are treated as an empty property map. */
  @Test
  public void testNullBaseCatalogPropertiesUseEmptyMap() {
    assertNullPropertiesRejected(catalog("catalog", null));
  }

  /** Verifies null generic Catalog properties are treated as an empty property map. */
  @Test
  public void testNullGenericCatalogPropertiesUseEmptyMap() {
    assertNullPropertiesRejected(genericCatalog("catalog", null));
  }

  /** Verifies a stale or superseded result is not reported as successful. */
  @Test
  public void testRejectedPassedResultIsNotReportedAsSuccessful() {
    Map<String, String> properties =
        Collections.singletonMap("credential-providers", "test-provider");
    loadCatalog("catalog", properties);
    CredentialProvider provider = mock(CredentialProvider.class);
    when(provider.getCredentialOptional(any())).thenReturn(Optional.of(mock(Credential.class)));
    testProvider = provider;
    when(connectionTestStore.recordTestResult(
            eq(NameIdentifier.of("metalake", "catalog")),
            eq("fileset"),
            eq(properties),
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
            eq(NameIdentifier.of("metalake", "catalog")),
            eq("fileset"),
            eq(properties),
            eq(ConnectionTestType.credential("test-provider")),
            eq(ConnectionTestResult.Status.PASSED),
            eq(123456L),
            eq(null));
  }

  private void assertNullPropertiesRejected(Catalog catalog) {
    loadCatalog(catalog);
    testCredentialType = null;

    Response response =
        target(
                "/web/metalakes/metalake/connections/catalog/credential-providers/"
                    + "test-provider/test")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .post(Entity.json("{\"path\":\"s3://bucket/path\"}"));

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(
        "Credential provider test-provider is not configured on Catalog metalake.catalog",
        errorResponse.getMessage());
    Assertions.assertNull(testCredentialType);
    verify(connectionTestStore, never())
        .recordTestResult(any(), any(), any(), any(), any(), anyLong(), any());
  }
}
