/*
 * Copyright 2026 Datastrato Pvt Ltd.
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
import com.datastrato.gravitino.dto.ConnectionDTO;
import com.datastrato.gravitino.dto.ConnectionTestStatusDTO;
import com.datastrato.gravitino.dto.responses.ConnectionListResponse;
import com.datastrato.gravitino.dto.responses.ConnectionOverviewResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
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
import org.apache.gravitino.utils.PrincipalUtils;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
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

  private final CatalogDispatcher catalogDispatcher = mock(CatalogManager.class);
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

    ConnectionDTO[] connections = connectionListResponse.getConnections();
    assertEquals(3, connections.length);

    assertEquals("sales_catalog", connections[0].getName());
    assertEquals("Iceberg REST", connections[0].getType());
    assertEquals("https://irc.acme.internal/iceberg/", connections[0].getEndpoint());
    assertEquals("s3-token", connections[0].getCredential());
    assertEquals(4L, connections[0].getSchemaCount());

    assertEquals("hive_legacy", connections[1].getName());
    assertEquals("Hive", connections[1].getType());
    assertEquals("thrift://hive:9083", connections[1].getEndpoint());
    assertEquals("kerberos-keytab", connections[1].getCredential());
    assertEquals(2L, connections[1].getSchemaCount());

    assertEquals("mysql_billing", connections[2].getName());
    assertEquals("MySQL", connections[2].getType());
    assertEquals("jdbc:mysql://localhost:3306/billing", connections[2].getEndpoint());
    assertEquals("--", connections[2].getCredential());
    assertEquals(1L, connections[2].getSchemaCount());
  }

  @Test
  public void testListConnectionsLoadsSchemaCountsConcurrently() {
    String metalake = "concurrent_metalake";
    CatalogDTO catalog1 =
        CatalogDTO.builder()
            .withName("catalog_1")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of("metastore.uris", "thrift://hive-1:9083"))
            .withAudit(AuditDTO.builder().build())
            .build();
    CatalogDTO catalog2 =
        CatalogDTO.builder()
            .withName("catalog_2")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of("metastore.uris", "thrift://hive-2:9083"))
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.listCatalogsInfo(Namespace.of(metalake)))
        .thenReturn(new Catalog[] {catalog1, catalog2});

    CountDownLatch schemaListingsStarted = new CountDownLatch(2);
    when(schemaDispatcher.listSchemas(any()))
        .thenAnswer(
            invocation -> {
              Namespace namespace = invocation.getArgument(0);
              assertTrue(
                  ((UserPrincipal) PrincipalUtils.getCurrentPrincipal()).getAccessToken().isEmpty(),
                  "Schema listings should retain the request principal instead of the worker "
                      + "thread's fallback principal");
              schemaListingsStarted.countDown();
              assertTrue(
                  schemaListingsStarted.await(5, TimeUnit.SECONDS),
                  "Schema listings should overlap instead of running sequentially");
              return new NameIdentifier[] {
                NameIdentifier.of(namespace.level(0), namespace.level(1), "schema")
              };
            });

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    ConnectionListResponse response = resp.readEntity(ConnectionListResponse.class);
    assertEquals(1L, response.getConnections()[0].getSchemaCount());
    assertEquals(1L, response.getConnections()[1].getSchemaCount());
  }

  @Test
  public void testListConnectionsReturnsUnavailableSchemaCountWhenLoadingFails()
      throws JsonProcessingException {
    String metalake = "unavailable_metalake";
    String catalogName = "unavailable_catalog";
    Namespace catalogNS = Namespace.of(metalake);
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName(catalogName)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(ImmutableMap.of("metastore.uris", "thrift://hive:9083"))
            .withAudit(AuditDTO.builder().build())
            .build();

    when(catalogDispatcher.listCatalogsInfo(catalogNS)).thenReturn(new Catalog[] {catalog});
    when(schemaDispatcher.listSchemas(Namespace.of(metalake, catalogName)))
        .thenThrow(new ConnectionFailedException("Backend is unavailable"));

    Response resp =
        target(String.format("/web/metalakes/%s/connections", metalake))
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    String responseBody = resp.readEntity(String.class);
    assertTrue(responseBody.contains("\"schemaCount\":null"));
    ConnectionListResponse response =
        ObjectMapperProvider.objectMapper().readValue(responseBody, ConnectionListResponse.class);
    assertEquals(1, response.getCatalogCount());
    assertEquals(1, response.getSystemCount());
    assertEquals(1, response.getConnections().length);
    assertNull(response.getConnections()[0].getSchemaCount());
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
    when(catalogDispatcher.loadCatalog(NameIdentifierUtil.ofCatalog(metalake, catalogName)))
        .thenReturn(loadedCatalog);
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
    assertEquals("jdbc-user-password", response.getConnections()[0].getCredential());
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
    when(catalogDispatcher.loadCatalog(NameIdentifierUtil.ofCatalog(metalake, catalogName)))
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
    assertEquals("--", response.getConnections()[0].getCredential());
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
                    "credential-providers",
                    "s3-token,gcs-token"))
            .withAudit(AuditDTO.builder().build())
            .build();
    when(catalogDispatcher.loadCatalog(identifier)).thenReturn(catalog);
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
}
