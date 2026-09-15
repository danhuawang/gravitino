/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import com.datastrato.gravitino.dto.ConnectionOverviewDTO;
import com.datastrato.gravitino.dto.ConnectionTestStatusDTO;
import com.datastrato.gravitino.dto.responses.ConnectionListResponse;
import com.datastrato.gravitino.dto.responses.ConnectionOverviewResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.HiddenPropertyMaskUtils;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.ThrowableFunction;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestConnectionOperations extends JerseyTest {

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private final CatalogManager catalogManager = mock(CatalogManager.class);
  private final CatalogDispatcher catalogDispatcher = catalogManager;
  private final SchemaDispatcher schemaDispatcher = mock(SchemaDispatcher.class);
  private final ConnectionTestStore connectionTestStore = mock(ConnectionTestStore.class);

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  /** {@inheritDoc} */
  @Override
  protected void configureClient(ClientConfig config) {
    config.register(ObjectMapperProvider.class);
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
    resourceConfig.register(ConnectionOperations.class);
    resourceConfig.register(ObjectMapperProvider.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
            bind(catalogManager).to(CatalogManager.class).ranked(2);
            bind(schemaDispatcher).to(SchemaDispatcher.class).ranked(2);
            bind(connectionTestStore).to(ConnectionTestStore.class).ranked(2);
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testListConnectionsSuccess() {
    String metalake = "metalake1";
    Namespace catalogNS = Namespace.of(metalake);

    CatalogDTO catalog1 =
        CatalogDTO.builder()
            .withName("sales_catalog")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "rest",
                    "uri",
                    "https://irc.acme.internal/iceberg/",
                    "credential-providers",
                    "s3-token"))
            .withAudit(AuditDTO.builder().build())
            .build();

    CatalogDTO catalog2 =
        CatalogDTO.builder()
            .withName("hive_legacy")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(
                ImmutableMap.of(
                    "metastore.uris", "thrift://hive:9083", "authentication.type", "Kerberos"))
            .withAudit(AuditDTO.builder().build())
            .build();

    CatalogDTO catalog3 =
        CatalogDTO.builder()
            .withName("mysql_billing")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-mysql")
            .withProperties(ImmutableMap.of("jdbc-url", "jdbc:mysql://localhost:3306/billing"))
            .withAudit(AuditDTO.builder().build())
            .build();

    when(catalogDispatcher.listCatalogsInfo(catalogNS))
        .thenReturn(new Catalog[] {catalog1, catalog2, catalog3});

    when(schemaDispatcher.listSchemas(Namespace.of(metalake, "sales_catalog")))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(metalake, "sales_catalog", "db1"),
              NameIdentifier.of(metalake, "sales_catalog", "db2"),
              NameIdentifier.of(metalake, "sales_catalog", "db3"),
              NameIdentifier.of(metalake, "sales_catalog", "db4")
            });
    when(schemaDispatcher.listSchemas(Namespace.of(metalake, "hive_legacy")))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(metalake, "hive_legacy", "schema1"),
              NameIdentifier.of(metalake, "hive_legacy", "schema2")
            });
    when(schemaDispatcher.listSchemas(Namespace.of(metalake, "mysql_billing")))
        .thenReturn(
            new NameIdentifier[] {NameIdentifier.of(metalake, "mysql_billing", "billing_schema")});

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ConnectionListResponse connectionListResponse = resp.readEntity(ConnectionListResponse.class);
    assertNotNull(connectionListResponse);
    assertEquals(0, connectionListResponse.getCode());
    assertEquals(3, connectionListResponse.getCatalogCount());
    assertEquals(3, connectionListResponse.getSystemCount());

    ConnectionOverviewDTO[] connections = connectionListResponse.getConnections();
    assertEquals(3, connections.length);

    assertEquals("sales_catalog", connections[0].getName());
    assertEquals(Catalog.Type.RELATIONAL, connections[0].getType());
    assertEquals("lakehouse-iceberg", connections[0].getProvider());
    assertEquals("https://irc.acme.internal/iceberg/", connections[0].getEndpoint());
    assertEquals("s3-token", connections[0].getCredentialProviders()[0].getType());

    assertEquals("hive_legacy", connections[1].getName());
    assertEquals(Catalog.Type.RELATIONAL, connections[1].getType());
    assertEquals("hive", connections[1].getProvider());
    assertEquals("thrift://hive:9083", connections[1].getEndpoint());
    assertEquals(0, connections[1].getCredentialProviders().length);

    assertEquals("mysql_billing", connections[2].getName());
    assertEquals(Catalog.Type.RELATIONAL, connections[2].getType());
    assertEquals("jdbc-mysql", connections[2].getProvider());
    assertEquals("jdbc:mysql://localhost:3306/billing", connections[2].getEndpoint());
    assertEquals(0, connections[2].getCredentialProviders().length);
  }

  @Test
  void testListConnectionsIncludesLatestPersistedTestStatuses() throws Exception {
    String metalake = "status_metalake";
    String[] names = {"passed", "failed", "untested", "unsupported"};
    Catalog[] catalogs = new Catalog[names.length];
    for (int i = 0; i < names.length; i++) {
      catalogs[i] =
          CatalogDTO.builder()
              .withName(names[i])
              .withType(Catalog.Type.RELATIONAL)
              .withProvider(i == 3 ? "lakehouse-generic" : "jdbc-mysql")
              .withProperties(ImmutableMap.of("jdbc-url", "jdbc:mysql://localhost:3306/db"))
              .withAudit(AuditDTO.builder().build())
              .build();
      when(schemaDispatcher.listSchemas(Namespace.of(metalake, names[i])))
          .thenReturn(new NameIdentifier[0]);
    }
    when(catalogDispatcher.listCatalogsInfo(Namespace.of(metalake))).thenReturn(catalogs);
    NameIdentifier passed = NameIdentifierUtil.ofCatalog(metalake, "passed");
    NameIdentifier failed = NameIdentifierUtil.ofCatalog(metalake, "failed");
    when(connectionTestStore.getValidTestResult(passed, ConnectionTestType.CATALOG))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.CATALOG,
                    1L,
                    ConnectionTestResult.Status.PASSED,
                    1787646600000L,
                    null)));
    when(connectionTestStore.getValidTestResult(failed, ConnectionTestType.CATALOG))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    11L,
                    ConnectionTestType.CATALOG,
                    1L,
                    ConnectionTestResult.Status.FAILED,
                    1787646601000L,
                    "Failed to connect to the catalog")));

    // Repeated list requests must retain the stored result without running a new probe.
    for (int request = 0; request < 2; request++) {
      try (Response response =
          target("/web/metalakes/" + metalake + "/connections")
              .request(MediaType.APPLICATION_JSON_TYPE)
              .accept("application/vnd.gravitino.v1+json")
              .get()) {
        assertEquals(200, response.getStatus());
        ConnectionListResponse list = response.readEntity(ConnectionListResponse.class);
        list.validate();
        ConnectionOverviewDTO[] connections = list.getConnections();
        assertEquals(4, connections.length);
        ConnectionTestStatusDTO success = connections[0].getTestStatus();
        assertEquals(ConnectionTestStatusDTO.PASSED, success.getStatus());
        assertEquals("2026-08-25T08:30:00Z", success.getLastTestedAt().toString());
        assertNull(success.getError());
        ConnectionTestStatusDTO failure = connections[1].getTestStatus();
        assertEquals(ConnectionTestStatusDTO.FAILED, failure.getStatus());
        assertEquals("2026-08-25T08:30:01Z", failure.getLastTestedAt().toString());
        assertEquals(ErrorConstants.CONNECTION_FAILED_CODE, failure.getError().getCode());
        assertEquals("ConnectionFailedException", failure.getError().getType());
        assertEquals("Failed to connect to the catalog", failure.getError().getMessage());
        ConnectionTestStatusDTO untested = connections[2].getTestStatus();
        assertTrue(untested.isSupported());
        assertEquals(ConnectionTestStatusDTO.NOT_TESTED, untested.getStatus());
        assertNull(untested.getLastTestedAt());
        assertNull(untested.getError());
        ConnectionTestStatusDTO unsupported = connections[3].getTestStatus();
        assertFalse(unsupported.isSupported());
        assertNull(unsupported.getStatus());
        assertNull(unsupported.getLastTestedAt());
        assertNull(unsupported.getError());
      }
    }
    Mockito.verify(catalogDispatcher, Mockito.never()).testConnection(any(NameIdentifier.class));
    Mockito.verify(connectionTestStore, Mockito.never())
        .getValidTestResult(
            NameIdentifierUtil.ofCatalog(metalake, "unsupported"), ConnectionTestType.CATALOG);
  }

  @Test
  void testListConnectionsDoesNotLoadSchemas() {
    String metalake = "unavailable_metalake";
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("unavailable_catalog")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of("metastore.uris", "thrift://hive:9083"))
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.listCatalogsInfo(Namespace.of(metalake)))
        .thenReturn(new Catalog[] {catalog});
    when(schemaDispatcher.listSchemas(any()))
        .thenThrow(new ConnectionFailedException("Backend is unavailable"));
    try (Response response =
        target("/web/metalakes/" + metalake + "/connections")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()) {
      assertEquals(200, response.getStatus());
      String body = response.readEntity(String.class);
      assertFalse(body.contains("schemaCount"));
      assertFalse(body.contains("\"credential\""));
    }
    Mockito.verifyNoInteractions(schemaDispatcher);
  }

  @Test
  public void testListConnectionsDoesNotExposeJdbcSecrets() throws JsonProcessingException {
    String metalake = "jdbc_secret_metalake";
    String catalogName = "jdbc_secret_catalog";
    Namespace catalogNS = Namespace.of(metalake);
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName(catalogName)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-mysql")
            .withProperties(
                ImmutableMap.<String, String>builder()
                    .put(
                        "jdbc-url",
                        "jdbc:mysql://url-user:url-secret@mysql.example.com:3306/SalesDb"
                            + "?password=query-secret&accessToken=query-token")
                    .build())
            .withAudit(AuditDTO.builder().build())
            .build();
    BaseCatalog<?> loadedCatalog = mock(BaseCatalog.class);
    when(loadedCatalog.propertiesWithCredentialProviders())
        .thenReturn(
            ImmutableMap.of(
                CredentialConstants.CREDENTIAL_PROVIDERS,
                "jdbc-user-password",
                "jdbc-user",
                "raw-user",
                "jdbc-password",
                "raw-password-secret"));

    when(catalogDispatcher.listCatalogsInfo(catalogNS)).thenReturn(new Catalog[] {catalog});
    mockDoWithCatalog(NameIdentifierUtil.ofCatalog(metalake, catalogName), loadedCatalog);
    when(schemaDispatcher.listSchemas(Namespace.of(metalake, catalogName)))
        .thenReturn(new NameIdentifier[0]);

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    String responseBody = resp.readEntity(String.class);
    assertFalse(responseBody.contains("url-secret"));
    assertFalse(responseBody.contains("query-secret"));
    assertFalse(responseBody.contains("query-token"));
    assertFalse(responseBody.contains("raw-user"));
    assertFalse(responseBody.contains("raw-password-secret"));

    ConnectionListResponse response =
        ObjectMapperProvider.objectMapper().readValue(responseBody, ConnectionListResponse.class);
    assertEquals(
        "jdbc:mysql://mysql.example.com:3306/SalesDb", response.getConnections()[0].getEndpoint());
    assertEquals(
        "jdbc-user-password", response.getConnections()[0].getCredentialProviders()[0].getType());
  }

  @Test
  public void testListConnectionsKeepsWorkingWhenCredentialInferenceFails() {
    String metalake = "credential_failure_metalake";
    String catalogName = "credential_failure_catalog";
    Namespace catalogNS = Namespace.of(metalake);
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName(catalogName)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-mysql")
            .withProperties(ImmutableMap.of("jdbc-url", "jdbc:mysql://mysql:3306/db"))
            .withAudit(AuditDTO.builder().build())
            .build();

    when(catalogDispatcher.listCatalogsInfo(catalogNS)).thenReturn(new Catalog[] {catalog});
    when(catalogManager.doWithCatalog(
            Mockito.eq(NameIdentifierUtil.ofCatalog(metalake, catalogName)), any()))
        .thenThrow(new IllegalStateException("Credential inference is unavailable"));
    when(schemaDispatcher.listSchemas(Namespace.of(metalake, catalogName)))
        .thenReturn(new NameIdentifier[0]);

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    ConnectionListResponse response = resp.readEntity(ConnectionListResponse.class);
    assertEquals(0, response.getConnections()[0].getCredentialProviders().length);
  }

  @Test
  public void testListConnectionsSystemDeduplication() {
    String metalake = "metalake_shared";
    Namespace catalogNS = Namespace.of(metalake);

    CatalogDTO catalog1 =
        CatalogDTO.builder()
            .withName("sales_catalog")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "rest",
                    "uri",
                    "https://irc.acme.internal/iceberg/",
                    "credential-providers",
                    "s3-token"))
            .withAudit(AuditDTO.builder().build())
            .build();

    CatalogDTO catalog2 =
        CatalogDTO.builder()
            .withName("events")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "rest",
                    "uri",
                    "https://irc.acme.internal/iceberg/",
                    "credential-providers",
                    "s3-token"))
            .withAudit(AuditDTO.builder().build())
            .build();

    when(catalogDispatcher.listCatalogsInfo(catalogNS))
        .thenReturn(new Catalog[] {catalog1, catalog2});
    when(schemaDispatcher.listSchemas(any())).thenReturn(new NameIdentifier[0]);

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    ConnectionListResponse response = resp.readEntity(ConnectionListResponse.class);
    assertEquals(2, response.getCatalogCount());
    assertEquals(1, response.getSystemCount());
  }

  @Test
  public void testListConnectionsEmpty() {
    String metalake = "empty_metalake";
    Namespace catalogNS = Namespace.of(metalake);

    when(catalogDispatcher.listCatalogsInfo(catalogNS)).thenReturn(new Catalog[0]);

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    ConnectionListResponse response = resp.readEntity(ConnectionListResponse.class);
    assertEquals(0, response.getCatalogCount());
    assertEquals(0, response.getSystemCount());
    assertEquals(0, response.getConnections().length);
  }

  @Test
  public void testListConnectionsBlankMetalake() {
    Response resp =
        target("/web/metalakes/ /connections")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
  }

  @Test
  void testLoadConnectionPassedOverview() throws JsonProcessingException {
    String metalake = "overview_metalake";
    String catalogName = "mysql_prod";
    NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, catalogName);
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName(catalogName)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-mysql")
            .withProperties(
                ImmutableMap.of(
                    "jdbc-url",
                    "jdbc:mysql://url-user:url-secret@mysql.example.com:3306/sales"
                        + "?password=query-secret&accessToken=query-token",
                    Catalog.CLOUD_NAME,
                    "aws",
                    Catalog.CLOUD_REGION_CODE,
                    "us-east-1",
                    "warehouse",
                    "s3a://warehouse-user:warehouse-secret@bucket/path",
                    CredentialConstants.CREDENTIAL_PROVIDERS,
                    HiddenPropertyMaskUtils.MASKED_VALUE))
            .withAudit(AuditDTO.builder().build())
            .build();
    BaseCatalog<?> liveCatalog = mock(BaseCatalog.class);
    when(liveCatalog.propertiesWithCredentialProviders())
        .thenReturn(
            ImmutableMap.of(CredentialConstants.CREDENTIAL_PROVIDERS, "s3-token,gcs-token"));
    mockDoWithCatalog(identifier, liveCatalog);
    when(catalogDispatcher.loadCatalog(identifier)).thenReturn(catalog);
    when(catalogDispatcher.listCatalogsInfo(Namespace.of(metalake)))
        .thenReturn(new Catalog[] {catalog});
    when(connectionTestStore.getValidTestResult(identifier, ConnectionTestType.CATALOG))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.CATALOG,
                    1L,
                    ConnectionTestResult.Status.PASSED,
                    1787646600000L,
                    null)));

    when(connectionTestStore.getValidTestResult(
            identifier, ConnectionTestType.credential("s3-token")))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.credential("s3-token"),
                    1L,
                    ConnectionTestResult.Status.PASSED,
                    1787646601000L,
                    null)));
    when(connectionTestStore.getValidTestResult(
            identifier, ConnectionTestType.credential("gcs-token")))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.credential("gcs-token"),
                    1L,
                    ConnectionTestResult.Status.FAILED,
                    1787646602000L,
                    "Failed to test the credential provider")));

    Response response =
        target(String.format("/web/metalakes/%s/connections/%s", metalake, catalogName))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"status\":\"PASSED\""));
    assertTrue(body.contains("\"lastTestedAt\":\"2026-08-25T08:30:00Z\""));
    assertTrue(body.contains("\"error\":null"));
    assertFalse(body.contains("url-user"));
    assertFalse(body.contains("url-secret"));
    assertFalse(body.contains("query-secret"));
    assertFalse(body.contains("query-token"));
    assertFalse(body.contains("warehouse-user"));
    assertFalse(body.contains("warehouse-secret"));
    assertFalse(body.contains("\"properties\""));
    ConnectionOverviewResponse overview =
        ObjectMapperProvider.objectMapper().readValue(body, ConnectionOverviewResponse.class);
    try (Response listResponse =
        target("/web/metalakes/" + metalake + "/connections")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()) {
      assertEquals(200, listResponse.getStatus());
      String listBody = listResponse.readEntity(String.class);
      ConnectionListResponse list =
          ObjectMapperProvider.objectMapper().readValue(listBody, ConnectionListResponse.class);
      list.validate();
      assertEquals(overview.getConnection(), list.getConnections()[0]);
      assertEquals(
          ObjectMapperProvider.objectMapper().readTree(body).get("connection"),
          ObjectMapperProvider.objectMapper().readTree(listBody).get("connections").get(0));
      assertFalse(listBody.contains("url-secret"));
      assertFalse(listBody.contains("query-secret"));
      assertFalse(listBody.contains("warehouse-secret"));
      Mockito.verifyNoInteractions(schemaDispatcher);
    }
    assertEquals(catalogName, overview.getConnection().getName());
    assertEquals(
        "jdbc:mysql://mysql.example.com:3306/sales", overview.getConnection().getEndpoint());
    assertEquals("aws", overview.getConnection().getCloudName());
    assertEquals("us-east-1", overview.getConnection().getCloudRegionCode());
    assertEquals(
        "2026-08-25T08:30:00Z",
        overview.getConnection().getTestStatus().getLastTestedAt().toString());
    assertEquals(2, overview.getConnection().getCredentialProviders().length);
    assertEquals("s3-token", overview.getConnection().getCredentialProviders()[0].getType());
    assertEquals(
        ConnectionTestStatusDTO.PASSED,
        overview.getConnection().getCredentialProviders()[0].getTestStatus().getStatus());
    assertEquals("gcs-token", overview.getConnection().getCredentialProviders()[1].getType());
    assertEquals(
        ConnectionTestStatusDTO.FAILED,
        overview.getConnection().getCredentialProviders()[1].getTestStatus().getStatus());
    assertEquals(
        ErrorConstants.INTERNAL_ERROR_CODE,
        overview.getConnection().getCredentialProviders()[1].getTestStatus().getError().getCode());
  }

  @Test
  void testLoadConnectionFailedOverviewHasSafeErrorWithoutStack() throws JsonProcessingException {
    String metalake = "failed_metalake";
    String catalogName = "failed_hive";
    NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, catalogName);
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName(catalogName)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of("metastore.uris", "thrift://hive:9083"))
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.loadCatalog(identifier)).thenReturn(catalog);
    when(connectionTestStore.getValidTestResult(identifier, ConnectionTestType.CATALOG))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    11L,
                    ConnectionTestType.CATALOG,
                    2L,
                    ConnectionTestResult.Status.FAILED,
                    1787646600000L,
                    "Failed to connect to the catalog")));

    Response response =
        target(String.format("/web/metalakes/%s/connections/%s", metalake, catalogName))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    String body = response.readEntity(String.class);
    assertTrue(body.contains("\"status\":\"FAILED\""));
    assertTrue(body.contains("\"code\":1007"));
    assertTrue(body.contains("\"type\":\"ConnectionFailedException\""));
    assertTrue(body.contains("\"message\":\"Failed to connect to the catalog\""));
    assertFalse(body.contains("stack"));
    assertFalse(body.contains("cause"));
  }

  @Test
  void testLoadConnectionNotTestedAndUnsupportedNullableShapes() {
    String metalake = "nullable_metalake";
    NameIdentifier hiveIdentifier = NameIdentifierUtil.ofCatalog(metalake, "hive");
    CatalogDTO hive =
        CatalogDTO.builder()
            .withName("hive")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of("metastore.uris", "thrift://hive:9083"))
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.loadCatalog(hiveIdentifier)).thenReturn(hive);
    when(connectionTestStore.getValidTestResult(hiveIdentifier, ConnectionTestType.CATALOG))
        .thenReturn(Optional.empty());

    String notTestedBody =
        target("/web/metalakes/nullable_metalake/connections/hive")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()
            .readEntity(String.class);
    assertTrue(notTestedBody.contains("\"supported\":true"));
    assertTrue(notTestedBody.contains("\"status\":\"NOT_TESTED\""));
    assertTrue(notTestedBody.contains("\"lastTestedAt\":null"));
    assertTrue(notTestedBody.contains("\"error\":null"));

    NameIdentifier modelIdentifier = NameIdentifierUtil.ofCatalog(metalake, "model");
    CatalogDTO model =
        CatalogDTO.builder()
            .withName("model")
            .withType(Catalog.Type.MODEL)
            .withProvider("model")
            .withProperties(ImmutableMap.of())
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.loadCatalog(modelIdentifier)).thenReturn(model);

    String unsupportedBody =
        target("/web/metalakes/nullable_metalake/connections/model")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()
            .readEntity(String.class);
    assertTrue(unsupportedBody.contains("\"supported\":false"));
    assertTrue(unsupportedBody.contains("\"status\":null"));
    assertTrue(unsupportedBody.contains("\"lastTestedAt\":null"));
    assertTrue(unsupportedBody.contains("\"error\":null"));
  }

  @Test
  void testFilesetWithFilesystemOperationsDisabledIsUnsupported() throws Exception {
    String metalake = "fileset_metalake";
    String catalogName = "metadata_only_fileset";
    NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, catalogName);
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName(catalogName)
            .withType(Catalog.Type.FILESET)
            .withProvider("fileset")
            .withProperties(
                ImmutableMap.of(
                    "location", "file:///tmp/fileset", "disable-filesystem-ops", "true"))
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.listCatalogsInfo(Namespace.of(metalake)))
        .thenReturn(new Catalog[] {catalog});
    when(catalogDispatcher.loadCatalog(identifier)).thenReturn(catalog);

    try (Response response =
        target("/web/metalakes/" + metalake + "/connections")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()) {
      assertEquals(200, response.getStatus());
      ConnectionTestStatusDTO status =
          response.readEntity(ConnectionListResponse.class).getConnections()[0].getTestStatus();
      assertFalse(status.isSupported());
      assertNull(status.getStatus());
      assertNull(status.getLastTestedAt());
      assertNull(status.getError());
    }

    try (Response response =
        target("/web/metalakes/" + metalake + "/connections/" + catalogName)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()) {
      assertEquals(200, response.getStatus());
      ConnectionTestStatusDTO status =
          response.readEntity(ConnectionOverviewResponse.class).getConnection().getTestStatus();
      assertFalse(status.isSupported());
      assertNull(status.getStatus());
      assertNull(status.getLastTestedAt());
      assertNull(status.getError());
    }

    Mockito.verify(connectionTestStore, Mockito.never())
        .getValidTestResult(identifier, ConnectionTestType.CATALOG);
  }

  @Test
  void testLoadConnectionNotFoundAndListJsonRegression() {
    String metalake = "missing_metalake";
    String catalogName = "missing_catalog";
    when(catalogDispatcher.loadCatalog(NameIdentifierUtil.ofCatalog(metalake, catalogName)))
        .thenThrow(new NoSuchCatalogException("Catalog does not exist"));

    Response missing =
        target(String.format("/web/metalakes/%s/connections/%s", metalake, catalogName))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), missing.getStatus());

    when(catalogDispatcher.listCatalogsInfo(Namespace.of(metalake))).thenReturn(new Catalog[0]);
    String listBody =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()
            .readEntity(String.class);
    assertEquals("{\"code\":0,\"connections\":[],\"catalogCount\":0,\"systemCount\":0}", listBody);
    assertFalse(listBody.contains("testStatus"));
  }

  @Test
  void testListConnectionsExactJsonWithMetadataAndCredentialResults() throws Exception {
    NameIdentifier identifier = NameIdentifier.of("contract", "pg_demo");
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("pg_demo")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-postgresql")
            .withComment("Reporting database")
            .withProperties(
                ImmutableMap.of(
                    "jdbc-url",
                    "jdbc:postgresql://hidden-user:hidden-password@db.example.com:5432/reporting"
                        + "?password=hidden-query-secret&sslmode=require",
                    Catalog.CLOUD_NAME,
                    "aws",
                    Catalog.CLOUD_REGION_CODE,
                    "us-east-1",
                    "credential-providers",
                    "s3-token,gcs-token,jdbc-user-password"))
            .withAudit(
                AuditDTO.builder()
                    .withCreator("alice")
                    .withCreateTime(Instant.parse("2026-08-01T01:02:03Z"))
                    .withLastModifier("bob")
                    .withLastModifiedTime(Instant.parse("2026-08-02T04:05:06Z"))
                    .build())
            .build();
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("contract")))
        .thenReturn(new Catalog[] {catalog});
    when(connectionTestStore.getValidTestResult(identifier, ConnectionTestType.CATALOG))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.CATALOG,
                    1L,
                    ConnectionTestResult.Status.FAILED,
                    1787646600000L,
                    "Failed to connect to the catalog")));
    when(connectionTestStore.getValidTestResult(
            identifier, ConnectionTestType.credential("s3-token")))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.credential("s3-token"),
                    1L,
                    ConnectionTestResult.Status.PASSED,
                    1787646601000L,
                    null)));
    when(connectionTestStore.getValidTestResult(
            identifier, ConnectionTestType.credential("gcs-token")))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.credential("gcs-token"),
                    1L,
                    ConnectionTestResult.Status.FAILED,
                    1787646602000L,
                    "Failed to test the credential provider")));

    // This literal is the wire contract, not serialized production DTOs or another endpoint's
    // output.
    assertListJsonContract(
        """
        {
          "code": 0,
          "connections": [{
            "name": "pg_demo",
            "type": "relational",
            "provider": "jdbc-postgresql",
            "comment": "Reporting database",
            "cloudName": "aws",
            "cloudRegionCode": "us-east-1",
            "audit": {
              "creator": "alice",
              "createTime": "2026-08-01T01:02:03Z",
              "lastModifier": "bob",
              "lastModifiedTime": "2026-08-02T04:05:06Z"
            },
            "endpoint": "jdbc:postgresql://db.example.com:5432/reporting?sslmode=require",
            "testStatus": {
              "supported": true,
              "status": "FAILED",
              "lastTestedAt": "2026-08-25T08:30:00Z",
              "error": {
                "code": 1007,
                "type": "ConnectionFailedException",
                "message": "Failed to connect to the catalog"
              }
            },
            "credentialProviders": [
              {
                "type": "s3-token",
                "testStatus": {
                  "supported": true,
                  "status": "PASSED",
                  "lastTestedAt": "2026-08-25T08:30:01Z",
                  "error": null
                }
              },
              {
                "type": "gcs-token",
                "testStatus": {
                  "supported": true,
                  "status": "FAILED",
                  "lastTestedAt": "2026-08-25T08:30:02Z",
                  "error": {
                    "code": 1002,
                    "type": "RuntimeException",
                    "message": "Failed to test the credential provider"
                  }
                }
              },
              {
                "type": "jdbc-user-password",
                "testStatus": {
                  "supported": true,
                  "status": "NOT_TESTED",
                  "lastTestedAt": null,
                  "error": null
                }
              }
            ]
          }],
          "catalogCount": 1,
          "systemCount": 1
        }
        """);
    Mockito.verifyNoInteractions(schemaDispatcher);
    Mockito.verify(catalogDispatcher, Mockito.never()).testConnection(any(NameIdentifier.class));
  }

  @Test
  void testListConnectionsExactJsonWithPassedUntestedAndUnsupportedCatalogs() throws Exception {
    CatalogDTO kafka =
        CatalogDTO.builder()
            .withName("events")
            .withType(Catalog.Type.MESSAGING)
            .withProvider("kafka")
            .withProperties(ImmutableMap.of("bootstrap.servers", "broker.example.com:9092"))
            .withAudit(AuditDTO.builder().withCreator("alice").build())
            .build();
    CatalogDTO fileset =
        CatalogDTO.builder()
            .withName("files")
            .withType(Catalog.Type.FILESET)
            .withProvider("fileset")
            .withProperties(ImmutableMap.of())
            .withAudit(AuditDTO.builder().withCreator("bob").build())
            .build();
    CatalogDTO model =
        CatalogDTO.builder()
            .withName("models")
            .withType(Catalog.Type.MODEL)
            .withProvider("model")
            .withProperties(ImmutableMap.of())
            .withAudit(AuditDTO.builder().withCreator("carol").build())
            .build();
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("contract")))
        .thenReturn(new Catalog[] {kafka, fileset, model});
    when(connectionTestStore.getValidTestResult(
            NameIdentifier.of("contract", "events"), ConnectionTestType.CATALOG))
        .thenReturn(
            Optional.of(
                new ConnectionTestResult(
                    10L,
                    ConnectionTestType.CATALOG,
                    1L,
                    ConnectionTestResult.Status.PASSED,
                    1787646600000L,
                    null)));

    // Optional metadata is omitted, whereas status-dependent null fields must remain explicit.
    assertListJsonContract(
        """
        {
          "code": 0,
          "connections": [
            {
              "name": "events",
              "type": "messaging",
              "provider": "kafka",
              "audit": {"creator": "alice"},
              "endpoint": "broker.example.com:9092",
              "testStatus": {
                "supported": true,
                "status": "PASSED",
                "lastTestedAt": "2026-08-25T08:30:00Z",
                "error": null
              },
              "credentialProviders": []
            },
            {
              "name": "files",
              "type": "fileset",
              "provider": "fileset",
              "audit": {"creator": "bob"},
              "endpoint": "--",
              "testStatus": {
                "supported": true,
                "status": "NOT_TESTED",
                "lastTestedAt": null,
                "error": null
              },
              "credentialProviders": []
            },
            {
              "name": "models",
              "type": "model",
              "provider": "model",
              "audit": {"creator": "carol"},
              "endpoint": "--",
              "testStatus": {
                "supported": false,
                "status": null,
                "lastTestedAt": null,
                "error": null
              },
              "credentialProviders": []
            }
          ],
          "catalogCount": 3,
          "systemCount": 3
        }
        """);
    Mockito.verifyNoInteractions(schemaDispatcher);
    Mockito.verify(catalogDispatcher, Mockito.never()).testConnection(any(NameIdentifier.class));
  }

  @Test
  void testJdbcEndpointsInListAndDetailJson() throws Exception {
    String[][] cases = {
      {"jdbc-oracle", "jdbc:oracle:thin:@oracle.sources.svc.cluster.local:1521/FREEPDB1"},
      {
        "jdbc-sqlserver",
        "jdbc:sqlserver://sqlserver.sources.svc.cluster.local:1433;databaseName=demo;trustServerCertificate=true"
      }
    };
    for (String[] example : cases) {
      CatalogDTO catalog =
          CatalogDTO.builder()
              .withName("jdbc_demo")
              .withType(Catalog.Type.RELATIONAL)
              .withProvider(example[0])
              .withProperties(ImmutableMap.of("jdbc-url", example[1] + ";password=hidden-secret"))
              .withAudit(AuditDTO.builder().build())
              .build();
      when(catalogDispatcher.listCatalogsInfo(Namespace.of("contract")))
          .thenReturn(new Catalog[] {catalog});
      when(catalogDispatcher.loadCatalog(NameIdentifier.of("contract", "jdbc_demo")))
          .thenReturn(catalog);
      for (boolean detail : new boolean[] {false, true}) {
        try (Response response =
            target("/web/metalakes/contract/connections" + (detail ? "/jdbc_demo" : ""))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get()) {
          assertEquals(200, response.getStatus());
          String body = response.readEntity(String.class);
          JsonNode root = new ObjectMapper().readTree(body);
          JsonNode connection = detail ? root.get("connection") : root.get("connections").get(0);
          assertEquals(example[1], connection.get("endpoint").asText());
          assertFalse(body.contains("hidden-secret"));
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void mockDoWithCatalog(NameIdentifier identifier, BaseCatalog<?> catalog) {
    Mockito.doAnswer(
            invocation -> {
              ThrowableFunction<BaseCatalog, Object> operation = invocation.getArgument(1);
              return operation.apply(catalog);
            })
        .when(catalogManager)
        .doWithCatalog(Mockito.eq(identifier), any());
  }

  private void assertListJsonContract(String expectedJson) throws JsonProcessingException {
    try (Response response =
        target("/web/metalakes/contract/connections")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get()) {
      assertEquals(200, response.getStatus());
      assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
      String actualJson = response.readEntity(String.class);
      // A plain tree parser avoids the production mapper's enum coercion and DTO defaults.
      ObjectMapper parser = new ObjectMapper();
      assertEquals(parser.readTree(expectedJson), parser.readTree(actualJson), actualJson);
    }
  }
}
