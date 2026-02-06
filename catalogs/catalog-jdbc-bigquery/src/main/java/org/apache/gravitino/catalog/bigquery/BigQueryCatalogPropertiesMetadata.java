/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog.bigquery;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.catalog.jdbc.JdbcCatalogPropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;

/**
 * Properties metadata for BigQuery catalog.
 *
 * <p>Defines BigQuery-specific catalog properties including project ID and authentication
 * credentials.
 */
public class BigQueryCatalogPropertiesMetadata extends JdbcCatalogPropertiesMetadata {

  // BigQuery catalog property keys
  public static final String PROJECT_ID = "project-id";
  // Proxy configuration properties
  public static final String PROXY_HOST = "proxy-host";
  public static final String PROXY_PORT = "proxy-port";
  public static final String PROXY_USERNAME = "proxy-username";
  public static final String PROXY_PASSWORD = "proxy-password";

  private static final Map<String, PropertyEntry<?>> BIGQUERY_CATALOG_PROPERTY_ENTRIES =
      ImmutableMap.<String, PropertyEntry<?>>builder()
          .put(
              PROJECT_ID,
              PropertyEntry.stringRequiredPropertyEntry(
                  PROJECT_ID, "Google Cloud Project ID", false /* immutable */, false /* hidden */))
          .put(
              PROXY_HOST,
              PropertyEntry.stringOptionalPropertyEntry(
                  PROXY_HOST,
                  "Proxy server hostname or IP address",
                  false /* immutable */,
                  null,
                  false /* hidden */))
          .put(
              PROXY_PORT,
              PropertyEntry.integerOptionalPropertyEntry(
                  PROXY_PORT,
                  "Proxy server port number",
                  false /* immutable */,
                  null,
                  false /* hidden */))
          .put(
              PROXY_USERNAME,
              PropertyEntry.stringOptionalPropertyEntry(
                  PROXY_USERNAME,
                  "Proxy authentication username",
                  false /* immutable */,
                  null,
                  false /* hidden */))
          .put(
              PROXY_PASSWORD,
              PropertyEntry.stringOptionalPropertyEntry(
                  PROXY_PASSWORD,
                  "Proxy authentication password",
                  false /* immutable */,
                  null,
                  true /* hidden */))
          .build();

  @Override
  protected Map<String, PropertyEntry<?>> specificPropertyEntries() {
    return BIGQUERY_CATALOG_PROPERTY_ENTRIES;
  }
}
