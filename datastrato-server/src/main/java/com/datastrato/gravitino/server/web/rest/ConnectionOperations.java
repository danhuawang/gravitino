/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import static com.datastrato.gravitino.server.web.rest.MetadataListingHelper.filterByExpression;

import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestSupportResolver;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import com.datastrato.gravitino.dto.ConnectionDTO;
import com.datastrato.gravitino.dto.ConnectionOverviewDTO;
import com.datastrato.gravitino.dto.ConnectionTestErrorDTO;
import com.datastrato.gravitino.dto.ConnectionTestStatusDTO;
import com.datastrato.gravitino.dto.CredentialProviderStatusDTO;
import com.datastrato.gravitino.dto.responses.ConnectionListResponse;
import com.datastrato.gravitino.dto.responses.ConnectionOverviewResponse;
import com.datastrato.gravitino.server.web.rest.converter.ConnectionConverter;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** REST operations for listing connections in Web Connect UI. */
@Path("/web/metalakes/{metalake}/connections")
public class ConnectionOperations {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionOperations.class);
  private static final int MAX_SCHEMA_COUNT_THREADS = 8;
  private static final long SCHEMA_COUNT_THREAD_KEEP_ALIVE_SECONDS = 60L;
  private static final AtomicInteger SCHEMA_COUNT_THREAD_COUNTER = new AtomicInteger();
  private static final ConnectionTestSupportResolver CONNECTION_TEST_SUPPORT_RESOLVER =
      new ConnectionTestSupportResolver();
  private static final ExecutorService SCHEMA_COUNT_EXECUTOR =
      new ThreadPoolExecutor(
          0,
          MAX_SCHEMA_COUNT_THREADS,
          SCHEMA_COUNT_THREAD_KEEP_ALIVE_SECONDS,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          runnable -> {
            Thread thread =
                new Thread(
                    runnable,
                    "connection-schema-count-" + SCHEMA_COUNT_THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.CallerRunsPolicy());

  private final CatalogDispatcher catalogDispatcher;
  private final SchemaDispatcher schemaDispatcher;
  private final ConnectionTestStore connectionTestStore;

  @Context private HttpServletRequest httpRequest;

  /**
   * Constructs a new ConnectionOperations.
   *
   * @param catalogDispatcher The catalog dispatcher.
   * @param schemaDispatcher The schema dispatcher.
   * @param connectionTestStore The persistent connection test store.
   */
  @Inject
  public ConnectionOperations(
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      ConnectionTestStore connectionTestStore) {
    this.catalogDispatcher = catalogDispatcher;
    this.schemaDispatcher = schemaDispatcher;
    this.connectionTestStore = connectionTestStore;
  }

  /**
   * Lists all connections under the given metalake.
   *
   * @param metalake The metalake name.
   * @return The response containing connections and summary statistics.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "")
  public Response listConnections(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake) {
    LOG.info("Received request to list connections for metalake: {}", metalake);

    if (StringUtils.isBlank(metalake)) {
      return Utils.illegalArguments(
          "Path param metalake cannot be blank", new IllegalArgumentException());
    }

    try {
      AuthorizationUtils.checkCurrentUser(metalake, PrincipalUtils.getCurrentUserName());
    } catch (ForbiddenException e) {
      return Utils.forbidden(e.getMessage(), e);
    }

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            Namespace catalogNS = NamespaceUtil.ofCatalog(metalake);
            Catalog[] catalogs = catalogDispatcher.listCatalogsInfo(catalogNS);
            catalogs =
                filterByExpression(
                    metalake,
                    AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
                    Entity.EntityType.CATALOG,
                    catalogs,
                    catalog -> NameIdentifierUtil.ofCatalog(metalake, catalog.name()));

            Map<String, String> credentialProviders =
                resolveCredentialProviders(metalake, catalogs);
            Map<String, Long> schemaCounts = listCatalogDirectChildCounts(metalake, catalogs);
            ConnectionDTO[] connections =
                ConnectionConverter.toConnectionDTOs(catalogs, schemaCounts, credentialProviders);
            int catalogCount = connections.length;
            int systemCount = ConnectionConverter.calculateSystemCount(connections);

            LOG.info(
                "Listed {} connections across {} systems under metalake: {}",
                catalogCount,
                systemCount,
                metalake);
            return Utils.ok(new ConnectionListResponse(connections, catalogCount, systemCount));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleCatalogException(OperationType.LIST, "", metalake, e);
    } catch (Throwable throwable) {
      return Utils.internalError("Unexpected error while listing connections", throwable);
    }
  }

  /**
   * Loads a single Catalog as a Connect overview with its latest valid manual test result.
   *
   * @param metalake The metalake name.
   * @param connection The Catalog name used as the connection name.
   * @return The connection overview response.
   */
  @GET
  @Path("{connection}")
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(
      expression = AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION,
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response loadConnection(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("connection") @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          String connection) {
    LOG.info("Received request to load connection overview: {}.{}", metalake, connection);
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, connection);
            Catalog catalog = catalogDispatcher.loadCatalog(identifier);
            ConnectionTestStatusDTO status = resolveTestStatus(identifier, catalog.provider());
            CredentialProviderStatusDTO[] credentialProviders =
                resolveCredentialProviderStatuses(identifier, resolveCredentialProviders(catalog));
            ConnectionOverviewDTO overview =
                ConnectionConverter.toConnectionOverviewDTO(catalog, status, credentialProviders);
            return Utils.ok(new ConnectionOverviewResponse(overview));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleCatalogException(OperationType.LOAD, connection, metalake, e);
    } catch (Throwable throwable) {
      return Utils.internalError("Unexpected error while loading connection overview", throwable);
    }
  }

  private ConnectionTestStatusDTO resolveTestStatus(NameIdentifier identifier, String provider) {
    if (!CONNECTION_TEST_SUPPORT_RESOLVER.supports(provider)) {
      return new ConnectionTestStatusDTO(false, null, null, null);
    }

    Optional<ConnectionTestResult> stored =
        connectionTestStore.getValidTestResult(identifier, ConnectionTestType.CATALOG);
    if (stored.isEmpty()) {
      return new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);
    }

    ConnectionTestResult result = stored.get();
    Instant lastTestedAt = Instant.ofEpochMilli(result.lastTestedAt());
    if (result.status() == ConnectionTestResult.Status.PASSED) {
      return new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.PASSED, lastTestedAt, null);
    }

    ConnectionTestErrorDTO error =
        new ConnectionTestErrorDTO(
            ErrorConstants.CONNECTION_FAILED_CODE,
            ConnectionFailedException.class.getSimpleName(),
            result.errorMessage());
    return new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.FAILED, lastTestedAt, error);
  }

  private CredentialProviderStatusDTO[] resolveCredentialProviderStatuses(
      NameIdentifier identifier, @Nullable String configuredProviders) {
    if (StringUtils.isBlank(configuredProviders)) {
      return new CredentialProviderStatusDTO[0];
    }

    Set<String> distinctTypes = new LinkedHashSet<>();
    for (String configuredProvider : configuredProviders.split(",")) {
      if (StringUtils.isNotBlank(configuredProvider)) {
        distinctTypes.add(configuredProvider.trim());
      }
    }

    List<CredentialProviderStatusDTO> statuses = new ArrayList<>();
    for (String credentialType : distinctTypes) {
      final String testType;
      try {
        testType = ConnectionTestType.credential(credentialType);
      } catch (IllegalArgumentException e) {
        LOG.warn(
            "Ignoring invalid credential provider type {} configured on Catalog {}",
            credentialType,
            identifier);
        continue;
      }

      Optional<ConnectionTestResult> stored =
          connectionTestStore.getValidTestResult(identifier, testType);
      ConnectionTestStatusDTO status;
      if (stored.isEmpty()) {
        status = new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);
      } else {
        ConnectionTestResult result = stored.get();
        Instant lastTestedAt = Instant.ofEpochMilli(result.lastTestedAt());
        if (result.status() == ConnectionTestResult.Status.PASSED) {
          status =
              new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.PASSED, lastTestedAt, null);
        } else {
          ConnectionTestErrorDTO error =
              new ConnectionTestErrorDTO(
                  ErrorConstants.INTERNAL_ERROR_CODE,
                  RuntimeException.class.getSimpleName(),
                  result.errorMessage());
          status =
              new ConnectionTestStatusDTO(
                  true, ConnectionTestStatusDTO.FAILED, lastTestedAt, error);
        }
      }
      statuses.add(new CredentialProviderStatusDTO(credentialType, status));
    }
    return statuses.toArray(new CredentialProviderStatusDTO[0]);
  }

  @Nullable
  private String resolveCredentialProviders(Catalog catalog) {
    Map<String, String> properties = catalog.properties();
    String providers =
        properties == null ? null : properties.get(CredentialConstants.CREDENTIAL_PROVIDERS);
    if (StringUtils.isNotBlank(providers) || !(catalog instanceof BaseCatalog)) {
      return providers;
    }
    return ((BaseCatalog<?>) catalog)
        .propertiesWithCredentialProviders()
        .get(CredentialConstants.CREDENTIAL_PROVIDERS);
  }

  private Map<String, String> resolveCredentialProviders(String metalake, Catalog[] catalogs) {
    Map<String, String> credentialProviders = new LinkedHashMap<>();
    for (Catalog catalog : catalogs) {
      Map<String, String> publicProperties = catalog.properties();
      if (publicProperties != null
          && StringUtils.isNotBlank(
              publicProperties.get(CredentialConstants.CREDENTIAL_PROVIDERS))) {
        continue;
      }

      try {
        Catalog loadedCatalog =
            catalogDispatcher.loadCatalog(NameIdentifierUtil.ofCatalog(metalake, catalog.name()));
        if (!(loadedCatalog instanceof BaseCatalog)) {
          continue;
        }

        String providers =
            ((BaseCatalog<?>) loadedCatalog)
                .propertiesWithCredentialProviders()
                .get(CredentialConstants.CREDENTIAL_PROVIDERS);
        if (StringUtils.isNotBlank(providers)) {
          credentialProviders.put(catalog.name(), providers);
        }
      } catch (RuntimeException e) {
        LOG.warn(
            "Failed to resolve credential providers for catalog {} under metalake {}; "
                + "the credential type will be unavailable",
            catalog.name(),
            metalake);
      }
    }
    return credentialProviders;
  }

  private Map<String, Long> listCatalogDirectChildCounts(String metalake, Catalog[] catalogs) {
    Principal principal = PrincipalUtils.getCurrentPrincipal();
    Map<String, Future<Long>> pendingCounts = new LinkedHashMap<>();
    for (Catalog catalog : catalogs) {
      Namespace catalogNamespace = Namespace.of(metalake, catalog.name());
      pendingCounts.put(
          catalog.name(),
          SCHEMA_COUNT_EXECUTOR.submit(() -> listVisibleSchemaCount(principal, catalogNamespace)));
    }

    Map<String, Long> directChildCounts = new LinkedHashMap<>();
    for (Map.Entry<String, Future<Long>> pendingCount : pendingCounts.entrySet()) {
      try {
        directChildCounts.put(pendingCount.getKey(), pendingCount.getValue().get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        pendingCounts.values().forEach(future -> future.cancel(true));
        LOG.warn(
            "Interrupted while retrieving schema counts under metalake {}; "
                + "remaining counts will be unavailable",
            metalake,
            e);
        break;
      } catch (ExecutionException e) {
        LOG.warn(
            "Failed to retrieve the schema count for catalog {} under metalake {}; "
                + "the count will be unavailable",
            pendingCount.getKey(),
            metalake,
            e.getCause());
      }
    }
    return directChildCounts;
  }

  private long listVisibleSchemaCount(Principal principal, Namespace catalogNamespace)
      throws Exception {
    if (principal == PrincipalUtils.getCurrentPrincipal()) {
      return MetadataListingHelper.listVisibleSchemaIdentifiers(schemaDispatcher, catalogNamespace)
          .length;
    }
    return PrincipalUtils.doAs(
        principal,
        () ->
            (long)
                MetadataListingHelper.listVisibleSchemaIdentifiers(
                        schemaDispatcher, catalogNamespace)
                    .length);
  }
}
