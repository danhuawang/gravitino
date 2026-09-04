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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
<<<<<<< HEAD
import org.apache.gravitino.encryption.IcebergEncryptionKmsKeyValidators;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
=======
>>>>>>> upstream/branch-1.3
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.authentication.AuthenticationConfig;
import org.apache.gravitino.iceberg.common.authentication.SupportsKerberos;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.iceberg.common.ops.KerberosAwareIcebergCatalogProxy;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.provider.DynamicIcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergCatalogWrapperManager implements AutoCloseable {

  public static final Logger LOG = LoggerFactory.getLogger(IcebergCatalogWrapperManager.class);

  private final Cache<String, CatalogWrapperForREST> catalogWrapperCache;

  private final IcebergConfigProvider configProvider;

  /**
   * True when the Iceberg REST service runs inside the Gravitino server. Standalone deployments
   * have no {@link GravitinoEnv}, so server-owned components such as the KMS client registry do not
   * exist.
   */
  private final boolean auxMode;

  public IcebergCatalogWrapperManager(
      Map<String, String> properties,
      IcebergConfigProvider configProvider,
      boolean auxMode,
      String metalakeName) {
    this.configProvider = configProvider;
    this.auxMode = auxMode;
    this.catalogWrapperCache =
        Caffeine.newBuilder()
            .expireAfterAccess(
                (new IcebergConfig(properties))
                    .get(IcebergConfig.ICEBERG_REST_CATALOG_CACHE_EVICTION_INTERVAL),
                TimeUnit.MILLISECONDS)
            .removalListener(
                (k, v, c) -> {
                  String catalogName = (String) k;
                  LOG.info("Remove IcebergCatalogWrapper cache {}.", catalogName);
                  closeIcebergCatalogWrapper((IcebergCatalogWrapper) v);
                })
            .scheduler(
                Scheduler.forScheduledExecutorService(
                    new ScheduledThreadPoolExecutor(
                        1,
                        new ThreadFactoryBuilder()
                            .setDaemon(true)
                            .setNameFormat("iceberg-catalog-wrapper-cleaner-%d")
                            .build())))
            .build();
    if (auxMode) {
      GravitinoEnv.getInstance()
          .catalogManager()
          .addCatalogCacheRemoveListener(
              ident -> {
                if (ident.namespace().level(0).equals(metalakeName)) {
                  catalogWrapperCache.invalidate(ident.name());
                  if (ident.name().equals(configProvider.getDefaultCatalogName())) {
                    catalogWrapperCache.invalidate(IcebergConstants.ICEBERG_REST_DEFAULT_CATALOG);
                  }
                }
              });
    }
  }

  /**
   * @param rawPrefix The path parameter is passed by a Jetty handler. The pattern is matching
   *     ([^/]*\/), end with /
   * @return the instance of IcebergCatalogWrapper.
   */
  public CatalogWrapperForREST getOps(String rawPrefix) {
    String catalogName = IcebergRESTUtils.getCatalogName(rawPrefix);
    return getCatalogWrapper(catalogName);
  }

  public CatalogWrapperForREST getCatalogWrapper(String catalogName) {
    if (LOG.isDebugEnabled()) {
      boolean cacheHit = catalogWrapperCache.getIfPresent(catalogName) != null;
      LOG.debug("getCatalogWrapper catalogName={} cacheHit={}", catalogName, cacheHit);
    }
    CatalogWrapperForREST catalogWrapperForREST =
        catalogWrapperCache.get(catalogName, k -> createCatalogWrapper(catalogName));
    // Reload conf to reset UserGroupInformation or icebergTableOps will always use
    // Simple auth.
    catalogWrapperForREST.reloadHadoopConf();
    return catalogWrapperForREST;
  }

  private CatalogWrapperForREST createCatalogWrapper(String catalogName) {
    IcebergRESTServerContext serverContext = IcebergRESTServerContext.getInstance();
    if (serverContext.isAuthorizationEnabled()
        && !(configProvider instanceof DynamicIcebergConfigProvider)) {
      throw new IllegalArgumentException(
          "Authorization is enabled. Set `gravitino.iceberg-rest.catalog-config-provider="
              + "dynamic-config-provider` in gravitino.conf for Iceberg REST.");
    }

    Optional<IcebergConfig> icebergConfig = configProvider.getIcebergCatalogConfig(catalogName);
    if (!icebergConfig.isPresent()) {
      throw new NoSuchCatalogException(
          "Couldn't find Iceberg configuration for catalog %s", catalogName);
    }
    return createCatalogWrapper(catalogName, icebergConfig.get());
  }

  // Overriding this method to create a new CatalogWrapperForREST for test;
  @VisibleForTesting
  protected CatalogWrapperForREST createCatalogWrapper(
      String catalogName, IcebergConfig icebergConfig) {
    // When the backend is a federated Iceberg REST catalog, use FederatedCatalogWrapper so
    // federation-aware behavior (FileIO property extraction, remote credential vending, remote
    // /v1/config defaults) is applied through polymorphic dispatch rather than scattered
    // instanceof checks. All other backends use the base CatalogWrapperForREST.
    IcebergCatalogBackend backend =
        IcebergCatalogBackend.valueOf(
            icebergConfig.get(IcebergConfig.CATALOG_BACKEND).toUpperCase(Locale.ROOT));
    IcebergEncryptionPolicyEvaluator.KmsKeyValidator kmsKeyValidator =
        resolveKmsKeyValidator(icebergConfig);
    CatalogWrapperForREST rest =
        backend == IcebergCatalogBackend.REST
            ? new FederatedCatalogWrapper(catalogName, icebergConfig, kmsKeyValidator)
            : new CatalogWrapperForREST(catalogName, icebergConfig, kmsKeyValidator);
    AuthenticationConfig authenticationConfig =
        new AuthenticationConfig(icebergConfig.getAllConfig());
    if (authenticationConfig.isKerberosAuth() && rest.getCatalog() instanceof SupportsKerberos) {
      return (CatalogWrapperForREST)
          new KerberosAwareIcebergCatalogProxy(rest).getProxy(catalogName, icebergConfig);
    }

    return rest;
  }

  /**
   * Resolves the key confirmation capability for one catalog.
   *
   * <p>Returns null, disabling confirmation, unless the service runs inside the Gravitino server
   * and the catalog names a KMS through {@code encryption-kms-source}. Checking the binding before
   * touching {@link GravitinoEnv} keeps catalogs that use no KMS independent of server-owned
   * components.
   *
   * @param icebergConfig resolved configuration for the catalog.
   * @return a validator bound to the server's KMS registry, or null when confirmation cannot apply.
   */
  @Nullable
  private IcebergEncryptionPolicyEvaluator.KmsKeyValidator resolveKmsKeyValidator(
      IcebergConfig icebergConfig) {
    if (!auxMode) {
      return null;
    }
    if (StringUtils.isBlank(
        icebergConfig.getAllConfig().get(IcebergConstants.ENCRYPTION_KMS_SOURCE))) {
      return null;
    }
    return IcebergEncryptionKmsKeyValidators.fromRegistry(
        GravitinoEnv.getInstance().kmsClientRegistry());
  }

  private void closeIcebergCatalogWrapper(IcebergCatalogWrapper catalogWrapper) {
    try {
      catalogWrapper.close();
    } catch (Exception ex) {
      LOG.warn("Close Iceberg table catalog wrapper fail: {}, {}", catalogWrapper, ex);
    }
  }

  @Override
  public void close() throws Exception {
    catalogWrapperCache.invalidateAll();
  }
}
