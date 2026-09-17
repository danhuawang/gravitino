/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc;

import com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle.OracleConnectorAdapter;
import com.datastrato.gravitino.trino.connector.catalog.jdbc.oracle.OracleConnectorAdapterProvider;
import com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver.SqlServerConnectorAdapter;
import com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver.SqlServerConnectorAdapterProvider;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.apache.gravitino.trino.connector.GravitinoConfig;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorAdapterProvider;
import org.apache.gravitino.trino.connector.catalog.DefaultCatalogConnectorFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestExtAdapterRegistration {

  @Test
  void testOracleAndSqlServerAreRegisteredThroughProviders() {
    DefaultCatalogConnectorFactory factory = new DefaultCatalogConnectorFactory(config());
    Assertions.assertTrue(factory.getSupportedCatalogProviders().contains("jdbc-oracle"));
    Assertions.assertTrue(factory.getSupportedCatalogProviders().contains("jdbc-sqlserver"));
  }

  @Test
  void testEachProviderIsServedByExactlyOneAdapterProvider() {
    Map<String, List<CatalogConnectorAdapterProvider>> byProvider =
        loadableProviders().stream()
            .collect(Collectors.groupingBy(CatalogConnectorAdapterProvider::provider));

    List<CatalogConnectorAdapterProvider> oracle = byProvider.get("jdbc-oracle");
    Assertions.assertEquals(1, oracle.size());
    Assertions.assertInstanceOf(OracleConnectorAdapterProvider.class, oracle.get(0));
    Assertions.assertInstanceOf(
        OracleConnectorAdapter.class, oracle.get(0).createAdapter(config()));

    List<CatalogConnectorAdapterProvider> sqlServer = byProvider.get("jdbc-sqlserver");
    Assertions.assertEquals(1, sqlServer.size());
    Assertions.assertInstanceOf(SqlServerConnectorAdapterProvider.class, sqlServer.get(0));
    Assertions.assertInstanceOf(
        SqlServerConnectorAdapter.class, sqlServer.get(0).createAdapter(config()));
  }

  // The open source test classpath registers deliberately broken providers; only the providers
  // shipped by this jar are of interest here.
  private static List<CatalogConnectorAdapterProvider> loadableProviders() {
    List<CatalogConnectorAdapterProvider> providers = new ArrayList<>();
    Iterator<CatalogConnectorAdapterProvider> iterator =
        ServiceLoader.load(CatalogConnectorAdapterProvider.class).iterator();
    while (true) {
      try {
        if (!iterator.hasNext()) {
          return providers;
        }
        CatalogConnectorAdapterProvider provider = iterator.next();
        if (provider.getClass().getName().startsWith("com.datastrato.")) {
          providers.add(provider);
        }
      } catch (ServiceConfigurationError ignored) {
        // skip the unloadable entry and keep iterating
      }
    }
  }

  private static GravitinoConfig config() {
    return new GravitinoConfig(
        ImmutableMap.of("gravitino.uri", "http://localhost:8090", "gravitino.metalake", "test"));
  }
}
