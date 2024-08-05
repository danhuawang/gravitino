/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.trino;

import com.datastrato.gravitino.trino.connector.catalog.jdbc.trino.jdbc.TrinoClusterConnectorAdapter;
import io.trino.spi.TrinoException;
import java.util.HashMap;
import org.apache.gravitino.trino.connector.GravitinoConfig;
import org.apache.gravitino.trino.connector.GravitinoErrorCode;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorContext;
import org.apache.gravitino.trino.connector.catalog.CatalogConnectorFactory;
import org.apache.gravitino.trino.connector.catalog.hive.HiveConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.iceberg.IcebergConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.jdbc.mysql.MySQLConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.jdbc.postgresql.PostgreSQLConnectorAdapter;
import org.apache.gravitino.trino.connector.catalog.memory.MemoryConnectorAdapter;
import org.apache.gravitino.trino.connector.metadata.GravitinoCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class use to create CatalogConnectorContext instance by given catalog. */
public class ClusterCatalogConnectorFactory implements CatalogConnectorFactory {
  private static final Logger LOG = LoggerFactory.getLogger(ClusterCatalogConnectorFactory.class);

  private final HashMap<String, CatalogConnectorContext.Builder> catalogBuilders = new HashMap<>();
  private final String region;

  public ClusterCatalogConnectorFactory(GravitinoConfig config) {
    this.region = config.getRegion();

    catalogBuilders.put("hive", new CatalogConnectorContext.Builder(new HiveConnectorAdapter()));
    catalogBuilders.put(
        "memory", new CatalogConnectorContext.Builder(new MemoryConnectorAdapter()));
    catalogBuilders.put(
        "lakehouse-iceberg", new CatalogConnectorContext.Builder(new IcebergConnectorAdapter()));
    catalogBuilders.put(
        "jdbc-mysql", new CatalogConnectorContext.Builder(new MySQLConnectorAdapter()));
    catalogBuilders.put(
        "jdbc-postgresql", new CatalogConnectorContext.Builder(new PostgreSQLConnectorAdapter()));
    catalogBuilders.put(
        "trino-cluster", new CatalogConnectorContext.Builder(new TrinoClusterConnectorAdapter()));
    LOG.info("Start the ClusterCatalogConnectorFactory");
  }

  public CatalogConnectorContext.Builder createCatalogConnectorContextBuilder(
      GravitinoCatalog catalog) {
    String catalogProvider = catalog.getProvider();
    if (!catalog.isSameRegion(region)) {
      catalogProvider = "trino-cluster";
    }

    CatalogConnectorContext.Builder builder = catalogBuilders.get(catalogProvider);
    if (builder == null) {
      String message = String.format("Unsupported catalog provider %s.", catalogProvider);
      LOG.error(message);
      throw new TrinoException(GravitinoErrorCode.GRAVITINO_UNSUPPORTED_CATALOG_PROVIDER, message);
    }

    // Avoid using the same builder object to prevent catalog creation errors.
    return builder.clone(catalog);
  }
}
