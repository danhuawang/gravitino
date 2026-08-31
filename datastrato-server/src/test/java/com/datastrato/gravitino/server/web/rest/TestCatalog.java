/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.connector.BaseCatalogPropertiesMetadata;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.HasPropertyMetadata;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

public class TestCatalog extends BaseCatalog<TestCatalog> {
  private static final PropertiesMetadata PROPERTIES_METADATA =
      new BaseCatalogPropertiesMetadata() {
        @Override
        protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
          return Collections.emptyMap();
        }
      };

  @Override
  public String shortName() {
    return "test";
  }

  @Override
  protected CatalogOperations newOps(Map config) {
    return new CatalogOperations() {

      @Override
      public void initialize(
          Map<String, String> config, CatalogInfo info, HasPropertyMetadata propertiesMetadata)
          throws RuntimeException {}

      @Override
      public void testConnection(
          NameIdentifier catalogIdent,
          Type type,
          String provider,
          String comment,
          Map<String, String> properties)
          throws Exception {}

      @Override
      public void close() throws IOException {}
    };
  }

  @Override
  public PropertiesMetadata catalogPropertiesMetadata() throws UnsupportedOperationException {
    return PROPERTIES_METADATA;
  }
}
