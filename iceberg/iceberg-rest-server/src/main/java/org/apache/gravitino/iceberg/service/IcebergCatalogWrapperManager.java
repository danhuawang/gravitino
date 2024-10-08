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
package org.apache.gravitino.iceberg.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapperProvider;
import org.apache.gravitino.iceberg.provider.ConfigBasedIcebergCatalogWrapperProvider;
import org.apache.gravitino.iceberg.provider.GravitinoBasedIcebergCatalogWrapperProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergCatalogWrapperManager implements AutoCloseable {
  public static final Logger LOG = LoggerFactory.getLogger(IcebergCatalogWrapperManager.class);

  private static final ImmutableMap<String, String> ICEBERG_TABLE_OPS_PROVIDER_NAMES =
      ImmutableMap.of(
          ConfigBasedIcebergCatalogWrapperProvider.CONFIG_BASE_ICEBERG_TABLE_OPS_PROVIDER_NAME,
          ConfigBasedIcebergCatalogWrapperProvider.class.getCanonicalName(),
          GravitinoBasedIcebergCatalogWrapperProvider
              .GRAVITINO_BASE_ICEBERG_TABLE_OPS_PROVIDER_NAME,
          GravitinoBasedIcebergCatalogWrapperProvider.class.getCanonicalName());

  private final Cache<String, IcebergCatalogWrapper> icebergTableOpsCache;

  private final IcebergCatalogWrapperProvider provider;

  public IcebergCatalogWrapperManager(Map<String, String> properties) {
    this.provider = createProvider(properties);
    this.provider.initialize(properties);
    this.icebergTableOpsCache =
        Caffeine.newBuilder()
            .expireAfterWrite(
                (new IcebergConfig(properties))
                    .get(IcebergConfig.ICEBERG_REST_CATALOG_CACHE_EVICTION_INTERVAL),
                TimeUnit.MILLISECONDS)
            .removalListener(
                (k, v, c) -> {
                  LOG.info("Remove IcebergCatalogWrapper cache {}.", k);
                  closeIcebergTableOps((IcebergCatalogWrapper) v);
                })
            .scheduler(
                Scheduler.forScheduledExecutorService(
                    new ScheduledThreadPoolExecutor(
                        1,
                        new ThreadFactoryBuilder()
                            .setDaemon(true)
                            .setNameFormat("table-ops-cleaner-%d")
                            .build())))
            .build();
  }

  /**
   * @param rawPrefix The path parameter is passed by a Jetty handler. The pattern is matching
   *     ([^/]*\/), end with /
   * @return the instance of IcebergCatalogWrapper.
   */
  public IcebergCatalogWrapper getOps(String rawPrefix) {
    String catalogName = getCatalogName(rawPrefix);
    IcebergCatalogWrapper tableOps =
        icebergTableOpsCache.get(catalogName, k -> provider.getIcebergTableOps(catalogName));
    // Reload conf to reset UserGroupInformation or icebergTableOps will always use
    // Simple auth.
    tableOps.reloadHadoopConf();
    return tableOps;
  }

  private String getCatalogName(String rawPrefix) {
    String prefix = shelling(rawPrefix);
    Preconditions.checkArgument(
        !IcebergConstants.GRAVITINO_DEFAULT_CATALOG.equals(prefix),
        String.format("%s is conflict with reserved key, please replace it", prefix));
    if (StringUtils.isBlank(prefix)) {
      return IcebergConstants.GRAVITINO_DEFAULT_CATALOG;
    }
    return prefix;
  }

  private IcebergCatalogWrapperProvider createProvider(Map<String, String> properties) {
    String providerName =
        (new IcebergConfig(properties)).get(IcebergConfig.ICEBERG_REST_CATALOG_PROVIDER);
    String className = ICEBERG_TABLE_OPS_PROVIDER_NAMES.getOrDefault(providerName, providerName);
    LOG.info("Load Iceberg catalog provider: {}.", className);
    try {
      Class<?> providerClz = Class.forName(className);
      return (IcebergCatalogWrapperProvider) providerClz.getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String shelling(String rawPrefix) {
    if (StringUtils.isBlank(rawPrefix)) {
      return rawPrefix;
    } else {
      // rawPrefix is a string matching ([^/]*/) which end with /
      Preconditions.checkArgument(
          rawPrefix.endsWith("/"), String.format("rawPrefix %s format is illegal", rawPrefix));
      return rawPrefix.substring(0, rawPrefix.length() - 1);
    }
  }

  private void closeIcebergTableOps(IcebergCatalogWrapper ops) {
    try {
      ops.close();
    } catch (Exception ex) {
      LOG.warn("Close Iceberg table ops fail: {}, {}", ops, ex);
    }
  }

  @Override
  public void close() throws Exception {
    icebergTableOpsCache.invalidateAll();
    if (provider instanceof AutoCloseable) {
      ((AutoCloseable) provider).close();
    }
  }
}
