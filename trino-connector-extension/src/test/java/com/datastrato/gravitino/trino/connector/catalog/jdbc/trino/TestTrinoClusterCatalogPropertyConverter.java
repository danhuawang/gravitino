/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.trino;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.trino.connector.catalog.jdbc.trino.jdbc.TrinoClusterConnectorAdapter;
import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.Map;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestTrinoClusterCatalogPropertyConverter {

  @Test
  public void testBuildTrinoClusterConnectorProperties() throws Exception {
    String name = "test_catalog";
    Map<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put("jdbc-url", "jdbc:mysql://localhost:5432/test")
            .put("jdbc-user", "test")
            .put("jdbc-password", "test")
            .put("cloud.trino.connection-url", "jdbc:trino://localhost:8080")
            .put("cloud.trino.connection-user", "admin")
            .put("cloud.trino.connection-password", "123")
            .build();
    Catalog mockCatalog =
        mockCatalog(name, "postgresql", "test catalog", Catalog.Type.RELATIONAL, properties);
    TrinoClusterConnectorAdapter adapter = new TrinoClusterConnectorAdapter();

    Map<String, String> config =
        adapter.buildInternalConnectorConfig(new GravitinoCatalog("test", mockCatalog));

    // test the converted properties, it's generating connector configuration by properties name
    // with the prefix 'cluster.'
    Assertions.assertEquals(
        config.get("connection-url"), "jdbc:trino://localhost:8080/" + mockCatalog.name());
    Assertions.assertEquals(config.get("connection-user"), "admin");
    Assertions.assertEquals(config.get("connection-password"), "123");
  }

  public static Catalog mockCatalog(
      String name,
      String provider,
      String comments,
      Catalog.Type type,
      Map<String, String> properties) {
    Catalog mockCatalog = mock(Catalog.class);
    when(mockCatalog.name()).thenReturn(name);
    when(mockCatalog.provider()).thenReturn(provider);
    when(mockCatalog.comment()).thenReturn(comments);
    when(mockCatalog.type()).thenReturn(type);
    when(mockCatalog.properties()).thenReturn(properties);

    Audit mockAudit = mock(Audit.class);
    when(mockAudit.creator()).thenReturn("gravitino");
    when(mockAudit.createTime()).thenReturn(Instant.now());
    when(mockCatalog.auditInfo()).thenReturn(mockAudit);
    return mockCatalog;
  }
}
