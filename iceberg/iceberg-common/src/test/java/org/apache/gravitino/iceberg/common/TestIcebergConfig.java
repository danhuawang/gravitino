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

package org.apache.gravitino.iceberg.common;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.iceberg.common.cache.LocalTableMetadataCache;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestIcebergConfig {

  private static final String BYPASS_PREFIX = "gravitino.bypass.";

  @Test
  public void testLoadIcebergConfig() {
    Map<String, String> properties =
        ImmutableMap.of(JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(), "1000");

    IcebergConfig icebergConfig = new IcebergConfig();
    icebergConfig.loadFromMap(properties, k -> k.startsWith("gravitino."));
    Assertions.assertEquals(
        JettyServerConfig.WEBSERVER_HTTP_PORT.getDefaultValue(),
        icebergConfig.get(JettyServerConfig.WEBSERVER_HTTP_PORT));

    IcebergConfig icebergRESTConfig2 = new IcebergConfig(properties);
    Assertions.assertEquals(1000, icebergRESTConfig2.get(JettyServerConfig.WEBSERVER_HTTP_PORT));
  }

  @Test
  public void testGovernedEncryptionStateIsPrivate() {
    IcebergConfig config =
        new IcebergConfig(ImmutableMap.of(IcebergConstants.ENCRYPTION_KMS_SOURCE, "openbao"), true);

    Assertions.assertTrue(config.isGovernedEncryptionConfigured());
    Assertions.assertFalse(
        config.getIcebergCatalogProperties().containsKey(IcebergConstants.ENCRYPTION_KMS_SOURCE));
    Assertions.assertFalse(new IcebergConfig(ImmutableMap.of()).isGovernedEncryptionConfigured());
  }

  /**
   * R2: a keyed catalog declares no format version of its own. Gravitino reads the version Iceberg
   * would use and refuses an encrypted table below the version encryption needs; it never writes
   * one. An operator's {@code table-default.format-version} is therefore forwarded verbatim, in
   * either spelling, including a 2 that will be refused later.
   */
  @Test
  public void testAKeyedConfigDeclaresNoFormatVersionOfItsOwnAndForwardsTheOperatorsVerbatim() {
    Assertions.assertFalse(
        keyedConfig(ImmutableMap.of())
            .getIcebergCatalogProperties()
            .containsKey(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION));
    Assertions.assertFalse(
        new IcebergConfig(ImmutableMap.of(), true)
            .getIcebergCatalogProperties()
            .containsKey(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION));

    for (String declared : new String[] {"2", "3"}) {
      for (String spelling :
          new String[] {
            IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
            BYPASS_PREFIX + IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION
          }) {
        Map<String, String> forwarded =
            keyedConfig(ImmutableMap.of(spelling, declared)).getIcebergCatalogProperties();
        Assertions.assertEquals(declared, forwarded.get(spelling), spelling + "=" + declared);
        Assertions.assertEquals(1, versionSpellings(forwarded), spelling + "=" + declared);
      }
    }
  }

  /** How many spellings of the table default format version the catalog properties carry. */
  private static long versionSpellings(Map<String, String> catalogProperties) {
    return catalogProperties.keySet().stream()
        .filter(key -> key.endsWith(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION))
        .count();
  }

  private static IcebergConfig keyedConfig(Map<String, String> additionalProperties) {
    Map<String, String> properties = new HashMap<>(additionalProperties);
    properties.put(IcebergEncryptionCatalogBinding.TABLE_DEFAULT_KEY_ID, "catalog-default-key");
    return new IcebergConfig(properties, true);
  }

  @Test
  public void testIcebergHttpPort() {
    Map<String, String> properties = ImmutableMap.of();
    IcebergConfig icebergConfig = new IcebergConfig(properties);
    JettyServerConfig jettyServerConfig = JettyServerConfig.fromConfig(icebergConfig);
    Assertions.assertEquals(
        IcebergConfig.DEFAULT_ICEBERG_REST_SERVICE_HTTP_PORT, jettyServerConfig.getHttpPort());
    Assertions.assertEquals(
        IcebergConfig.DEFAULT_ICEBERG_REST_SERVICE_HTTPS_PORT, jettyServerConfig.getHttpsPort());

    properties =
        ImmutableMap.of(
            JettyServerConfig.WEBSERVER_HTTP_PORT.getKey(),
            "1000",
            JettyServerConfig.WEBSERVER_HTTPS_PORT.getKey(),
            "1001");
    icebergConfig = new IcebergConfig(properties);
    jettyServerConfig = JettyServerConfig.fromConfig(icebergConfig);
    Assertions.assertEquals(1000, jettyServerConfig.getHttpPort());
    Assertions.assertEquals(1001, jettyServerConfig.getHttpsPort());
  }

  @Test
  public void testTableMetadataCacheDefaults() {
    IcebergConfig icebergConfig = new IcebergConfig(ImmutableMap.of());
    Assertions.assertEquals(
        LocalTableMetadataCache.class.getName(),
        icebergConfig.get(IcebergConfig.TABLE_METADATA_CACHE_IMPL));
    Assertions.assertEquals(1000, icebergConfig.get(IcebergConfig.TABLE_METADATA_CACHE_CAPACITY));
    Assertions.assertEquals(
        IcebergConfig.TABLE_METADATA_CACHE_IMPL.getDefaultValue(),
        icebergConfig.get(IcebergConfig.TABLE_METADATA_CACHE_IMPL));
    Assertions.assertEquals(
        IcebergConfig.TABLE_METADATA_CACHE_CAPACITY.getDefaultValue(),
        icebergConfig.get(IcebergConfig.TABLE_METADATA_CACHE_CAPACITY));
  }

  @Test
  public void testDisableRestAuthzConfigKey() {
    Map<String, String> propertiesWithNewKey =
        ImmutableMap.of(IcebergConfig.ICEBERG_REST_DISABLE_REST_AUTHZ.getKey(), "false");
    IcebergConfig icebergConfigWithNewKey = new IcebergConfig(propertiesWithNewKey);
    Assertions.assertFalse(
        icebergConfigWithNewKey.get(IcebergConfig.ICEBERG_REST_DISABLE_REST_AUTHZ));
  }

  @Test
  public void testAsyncCleanupDefaults() {
    IcebergConfig config = new IcebergConfig(ImmutableMap.of());
    Assertions.assertEquals(2, config.get(IcebergConfig.ASYNC_CLEANUP_WORKER_THREADS));
    Assertions.assertEquals(4, config.get(IcebergConfig.ASYNC_CLEANUP_DELETE_THREADS));
    Assertions.assertEquals(1000, config.get(IcebergConfig.ASYNC_CLEANUP_DELETE_BATCH_SIZE));
    Assertions.assertEquals(5, config.get(IcebergConfig.ASYNC_CLEANUP_POLL_INTERVAL_SECS));
    Assertions.assertEquals(300, config.get(IcebergConfig.ASYNC_CLEANUP_HEARTBEAT_TIMEOUT_SECS));
    Assertions.assertEquals(5, config.get(IcebergConfig.ASYNC_CLEANUP_MAX_ATTEMPTS));
    Assertions.assertEquals(720, config.get(IcebergConfig.ASYNC_CLEANUP_RETENTION_HOURS));
  }

  @Test
  public void testRESTCatalogBackendClientTimeoutDefaults() {
    IcebergConfig icebergConfig = new IcebergConfig(ImmutableMap.of());

    Assertions.assertEquals(
        10000, icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        60000, icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
    Assertions.assertEquals(
        IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS.getDefaultValue(),
        icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS.getDefaultValue(),
        icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
  }

  @Test
  public void testRESTCatalogBackendClientTimeoutConfigKeys() {
    IcebergConfig icebergConfig =
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS.getKey(),
                "1234",
                IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS.getKey(),
                "5678"));

    Assertions.assertEquals(
        1234, icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        5678, icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
  }

  @Test
  public void testRESTCatalogBackendClientTimeoutIcebergPropertyAliases() {
    IcebergConfig icebergConfig =
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConstants.ICEBERG_REST_CLIENT_CONNECTION_TIMEOUT_MS,
                "2345",
                IcebergConstants.ICEBERG_REST_CLIENT_SOCKET_TIMEOUT_MS,
                "6789"));

    Assertions.assertEquals(
        2345, icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));
    Assertions.assertEquals(
        6789, icebergConfig.get(IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
  }

  @Test
  public void testRESTCatalogBackendClientTimeoutRejectsNonPositiveValues() {
    IcebergConfig zeroConnectionTimeoutConfig =
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS.getKey(), "0"));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            zeroConnectionTimeoutConfig.get(
                IcebergConfig.REST_CATALOG_BACKEND_CLIENT_CONNECTION_TIMEOUT_MS));

    IcebergConfig negativeSocketTimeoutConfig =
        new IcebergConfig(
            ImmutableMap.of(
                IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS.getKey(), "-1"));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            negativeSocketTimeoutConfig.get(
                IcebergConfig.REST_CATALOG_BACKEND_CLIENT_SOCKET_TIMEOUT_MS));
  }
}
