/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.bigquery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.services.bigquery.model.Job;
import com.google.auth.oauth2.GoogleCredentials;
import com.simba.googlebigquery.googlebigquery.client.BQClient;
import com.simba.googlebigquery.googlebigquery.client.BQHttpTransportFactory;
import com.simba.googlebigquery.googlebigquery.client.authentication.AbstractAuthentication;
import com.simba.googlebigquery.googlebigquery.core.BQConnectionOptions;
import com.simba.googlebigquery.googlebigquery.core.propertyutils.PrivateServiceConnectUris;
import com.simba.googlebigquery.support.ILogger;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.PropertyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

/** Unit tests for BigQueryCatalog. */
public class TestBigQueryCatalog {

  @Test
  void testShortName() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    assertEquals("jdbc-bigquery", catalog.shortName());
  }

  @Test
  void testNewCapability() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    assertNotNull(catalog.newCapability());
    assertTrue(catalog.newCapability() instanceof BigQueryCatalogCapability);
  }

  @Test
  void testPropertiesMetadata() {
    BigQueryCatalog catalog = new BigQueryCatalog();

    assertNotNull(catalog.catalogPropertiesMetadata());
    assertTrue(catalog.catalogPropertiesMetadata() instanceof BigQueryCatalogPropertiesMetadata);

    assertNotNull(catalog.schemaPropertiesMetadata());
    assertTrue(catalog.schemaPropertiesMetadata() instanceof BigQuerySchemaPropertiesMetadata);

    assertNotNull(catalog.tablePropertiesMetadata());
    assertTrue(catalog.tablePropertiesMetadata() instanceof BigQueryTablePropertiesMetadata);
  }

  @Test
  void testWithCatalogConf() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");
    config.put("jdbc-url", "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;");

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = catalog.withCatalogConf(config);

    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
    assertEquals("test-project", configuredCatalog.getClientPool().getProjectId());
  }

  @Test
  void testBuildJdbcUrlWithExistingAuth() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");
    config.put(
        "jdbc-url",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=test-project;OAuthType=0;");

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = catalog.withCatalogConf(config);

    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
  }

  @Test
  void testBuildJdbcUrlWithoutAuth() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");
    config.put("jdbc-user", "test@example.com");

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = catalog.withCatalogConf(config);

    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
  }

  /**
   * Regression test for #2165: the catalog must declare the inherited JDBC properties so the API
   * and UI can discover them. Previously only 5 BigQuery-specific entries were exposed.
   */
  @Test
  void testCatalogPropertiesDeclareJdbcEntries() {
    BigQueryCatalog catalog = new BigQueryCatalog();
    Map<String, PropertyEntry<?>> entries = catalog.catalogPropertiesMetadata().propertyEntries();

    // BigQuery-specific
    assertTrue(entries.containsKey("project-id"), "project-id must be declared");
    assertTrue(entries.get("project-id").isRequired(), "project-id must be required");

    // Inherited JDBC properties must be present after the super merge.
    assertTrue(entries.containsKey("jdbc-url"), "jdbc-url must be declared");
    assertTrue(entries.containsKey("jdbc-driver"), "jdbc-driver must be declared");
    assertTrue(entries.containsKey("jdbc-user"), "jdbc-user must be declared");
    assertTrue(entries.containsKey("jdbc-password"), "jdbc-password must be declared");

    // jdbc-user and jdbc-password are genuinely required for BigQuery service-account auth.
    assertTrue(entries.get("jdbc-user").isRequired(), "jdbc-user must be required");
    assertTrue(entries.get("jdbc-password").isRequired(), "jdbc-password must be required");

    // jdbc-url and jdbc-driver are optional with BigQuery defaults (built / well-known).
    assertEquals(false, entries.get("jdbc-url").isRequired(), "jdbc-url must be optional");
    assertEquals(false, entries.get("jdbc-driver").isRequired(), "jdbc-driver must be optional");
    assertEquals(
        BigQueryCatalogPropertiesMetadata.DEFAULT_JDBC_DRIVER,
        entries.get("jdbc-driver").getDefaultValue(),
        "jdbc-driver default must be the Simba driver class");
    assertEquals(
        BigQueryCatalogPropertiesMetadata.DEFAULT_JDBC_URL,
        entries.get("jdbc-url").getDefaultValue(),
        "jdbc-url default must be the BigQuery base URL");

    // jdbc-password must remain hidden (credential locator).
    assertTrue(entries.get("jdbc-password").isHidden(), "jdbc-password must be hidden");
  }

  /**
   * Regression test for #2165: a create missing a required credential property must be refused at
   * create time (fail fast in withCatalogConf), not accepted and then failing on first use.
   */
  @Test
  void testWithCatalogConfRejectsMissingProjectId() {
    Map<String, String> config = new HashMap<>();
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("project-id"));
  }

  @Test
  void testWithCatalogConfRejectsMissingUser() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("jdbc-user"));
  }

  @Test
  void testWithCatalogConfRejectsMissingPassword() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("jdbc-password"));
  }

  /**
   * Regression test: a catalog whose jdbc-url already carries the project and authentication
   * parameters must keep working with only project-id and jdbc-password (no separate jdbc-user), as
   * it did before the create-time validation was added. The service account email is embedded in
   * the URL, so jdbc-user is not required on this path.
   */
  @Test
  void testWithCatalogConfPreAuthenticatedUrlWithoutJdbcUser() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-password", "/path/to/key.json");
    config.put(
        "jdbc-url",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=test-project;"
            + "OAuthType=0;OAuthServiceAcctEmail=test@example.com;OAuthPvtKeyPath=/path/to/key.json;");
    // Intentionally no jdbc-user.

    BigQueryCatalog catalog = new BigQueryCatalog();
    BigQueryCatalog configuredCatalog = assertDoesNotThrow(() -> catalog.withCatalogConf(config));
    assertNotNull(configuredCatalog);
    assertNotNull(configuredCatalog.getClientPool());
  }

  @Test
  void testWithCatalogConfRejectsProxyPortWithoutHost() {
    Map<String, String> config = new HashMap<>();
    config.put("project-id", "test-project");
    config.put("jdbc-user", "test@example.com");
    config.put("jdbc-password", "/path/to/key.json");
    config.put("proxy-host", "proxy.example.com");
    // proxy-port intentionally omitted

    BigQueryCatalog catalog = new BigQueryCatalog();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> catalog.withCatalogConf(config));
    assertTrue(e.getMessage().contains("proxy-port"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=test-project;OAuthType=0;",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443; ;\t;ProjectId=test-project;"
            + " ;OAuthType=0; ;\t;",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=test-project;OAuthType=0;"
            + "EnableHighThroughPutAPI=1;ForceHTAPI=1;EnableWriteAPI=1;",
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;"
            + "enablehighthroughputapi=1; FORCEHTAPI =1;EnableWriteAPI=1;ForceHTAPI=1;"
      })
  void testStorageApisDisabled(String jdbcUrl) throws Exception {
    Map<String, String> config =
        Map.of(
            "project-id", "test-project",
            "jdbc-user", "test@example.com",
            "jdbc-password", "/path/to/key.json",
            "jdbc-url", jdbcUrl,
            "gravitino.bypass.connectionProperties",
                " ;EnableHighThroughPutAPI=1;ForceHTAPI=1;\t;EnableWriteAPI=1;Timeout=42;ProxyPwd=secret ; ;");
    BigQueryCatalog catalog = new BigQueryCatalog().withCatalogConf(config);
    Field confField = BaseCatalog.class.getDeclaredField("conf");
    confField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, String> effectiveConfig = (Map<String, String>) confField.get(catalog);

    String effectiveUrl = effectiveConfig.get("jdbc-url");
    assertTrue(effectiveUrl.contains("ProjectId=test-project;"));
    assertTrue(effectiveUrl.contains("OAuthType=0;"));
    assertEquals(1, effectiveUrl.split("EnableHighThroughPutAPI=0;", -1).length - 1);
    assertTrue(effectiveUrl.endsWith("EnableHighThroughPutAPI=0;ForceHTAPI=0;EnableWriteAPI=0;"));
    assertFalse(effectiveUrl.toLowerCase().contains("api=1"));
    for (String property : effectiveUrl.split(";")) {
      assertFalse(property.trim().isEmpty());
    }
    assertEquals(
        "Timeout=42;ProxyPwd=secret ;EnableHighThroughPutAPI=0;ForceHTAPI=0;EnableWriteAPI=0;",
        effectiveConfig.get("gravitino.bypass.connectionProperties"));
    assertEquals(jdbcUrl, config.get("jdbc-url"));
    assertStorageApiClientsNotCreated(effectiveUrl);
    catalog.close();
  }

  @Test
  void testStorageApisDisabledForGeneratedUrl() throws Exception {
    BigQueryCatalog catalog =
        new BigQueryCatalog()
            .withCatalogConf(
                Map.of(
                    "project-id",
                    "test-project",
                    "jdbc-user",
                    "test@example.com",
                    "jdbc-password",
                    "/path/to/key.json"));
    Field confField = BaseCatalog.class.getDeclaredField("conf");
    confField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, String> config = (Map<String, String>) confField.get(catalog);
    assertTrue(
        config
            .get("jdbc-url")
            .endsWith("EnableHighThroughPutAPI=0;ForceHTAPI=0;EnableWriteAPI=0;"));
    catalog.close();
  }

  private void assertStorageApiClientsNotCreated(String jdbcUrl) throws Exception {
    Properties properties = new Properties();
    properties.load(
        new StringReader(jdbcUrl.substring(jdbcUrl.indexOf(';') + 1).replace(';', '\n')));
    BQConnectionOptions options = new BQConnectionOptions();
    options.m_enableHighThroughPutAPI =
        !"0".equals(properties.getProperty("EnableHighThroughPutAPI"));
    options.m_forceHTAPI = !"0".equals(properties.getProperty("ForceHTAPI"));
    options.m_enableWriteAPI = !"0".equals(properties.getProperty("EnableWriteAPI"));
    ILogger logger = mock(ILogger.class);
    options.m_baseUrls = new PrivateServiceConnectUris(logger);
    AbstractAuthentication authentication = mock(AbstractAuthentication.class);
    when(authentication.buildCredentials()).thenReturn(mock(GoogleCredentials.class));
    AtomicReference<String> unexpectedRequest = new AtomicReference<>();
    HttpTransport offlineTransport =
        new HttpTransport() {
          @Override
          protected LowLevelHttpRequest buildRequest(String method, String url) {
            unexpectedRequest.compareAndSet(null, method + " " + url);
            throw new AssertionError("Unexpected HTTP request: " + method + " " + url);
          }
        };
    // Exercise the driver's initialization, but fail immediately if it attempts an HTTP request.
    try (MockedConstruction<BQHttpTransportFactory> transports =
        mockConstruction(
            BQHttpTransportFactory.class,
            (factory, context) -> when(factory.create()).thenReturn(offlineTransport))) {
      for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
        BQClient client = spy(new BQClient(logger, options));
        doReturn(new Job()).when(client).prepare(any());
        client.authenticateAndTest(authentication, null, null);
        verify(client).prepare(any());
        assertNull(client.getBigQueryStorageReadClient());
        assertNull(client.getBigQueryStorageWriteClient());
      }
      assertFalse(transports.constructed().isEmpty());
    }
    // A driver catching AssertionError must not hide an attempted request from the test.
    assertNull(unexpectedRequest.get(), "The driver attempted an unexpected HTTP request");
  }
}
