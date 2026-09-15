/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static com.datastrato.gravitino.server.web.rest.MetadataListingHelper.filterByExpression;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestSupportResolver;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import com.datastrato.gravitino.dto.ConnectionOverviewDTO;
import com.datastrato.gravitino.dto.ConnectionTestErrorDTO;
import com.datastrato.gravitino.dto.ConnectionTestStatusDTO;
import com.datastrato.gravitino.dto.CredentialProviderStatusDTO;
import com.datastrato.gravitino.dto.responses.ConnectionListResponse;
import com.datastrato.gravitino.dto.responses.ConnectionOverviewResponse;
import com.datastrato.gravitino.server.web.rest.converter.ConnectionConverter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.connector.HiddenPropertyMaskUtils;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.metrics.MetricNames;
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
  private static final ConnectionTestSupportResolver CONNECTION_TEST_SUPPORT_RESOLVER =
      new ConnectionTestSupportResolver();

  private final CatalogDispatcher catalogDispatcher;
  private final CatalogManager catalogManager;
  private final ConnectionTestStore connectionTestStore;

  @Context private HttpServletRequest httpRequest;

  /**
   * Constructs a new ConnectionOperations.
   *
   * @param catalogDispatcher The catalog dispatcher.
   * @param catalogManager The catalog manager.
   * @param connectionTestStore The persistent connection test store.
   */
  @Inject
  public ConnectionOperations(
      CatalogDispatcher catalogDispatcher,
      CatalogManager catalogManager,
      ConnectionTestStore connectionTestStore) {
    this.catalogDispatcher = catalogDispatcher;
    this.catalogManager = catalogManager;
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
  @Timed(name = "web-list-connections." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-list-connections", absolute = true)
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

            ConnectionOverviewDTO[] connections = new ConnectionOverviewDTO[catalogs.length];
            for (int i = 0; i < catalogs.length; i++) {
              Catalog catalog = catalogs[i];
              NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, catalog.name());
              String providers = resolveCredentialProviders(identifier, catalog);
              connections[i] = toConnectionOverview(identifier, catalog, providers);
            }
            int catalogCount = connections.length;
            int systemCount = ConnectionConverter.calculateOverviewSystemCount(connections);

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
  @Timed(name = "web-load-connection." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-load-connection", absolute = true)
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
            ConnectionOverviewDTO overview =
                toConnectionOverview(
                    identifier, catalog, resolveCredentialProviders(identifier, catalog));
            return Utils.ok(new ConnectionOverviewResponse(overview));
          });
    } catch (Exception e) {
      return ExceptionHandlers.handleCatalogException(OperationType.LOAD, connection, metalake, e);
    } catch (Throwable throwable) {
      return Utils.internalError("Unexpected error while loading connection overview", throwable);
    }
  }

  private ConnectionOverviewDTO toConnectionOverview(
      NameIdentifier identifier, Catalog catalog, @Nullable String configuredProviders) {
    return ConnectionConverter.toConnectionOverviewDTO(
        catalog,
        resolveTestStatus(identifier, catalog),
        resolveCredentialProviderStatuses(identifier, configuredProviders));
  }

  private ConnectionTestStatusDTO resolveTestStatus(NameIdentifier identifier, Catalog catalog) {
    if (!CONNECTION_TEST_SUPPORT_RESOLVER.supports(catalog.provider(), catalog.properties())) {
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
  private String resolveCredentialProviders(NameIdentifier identifier, Catalog catalog) {
    Map<String, String> properties = catalog.properties();
    String providers =
        properties == null ? null : properties.get(CredentialConstants.CREDENTIAL_PROVIDERS);
    if (StringUtils.isNotBlank(providers)
        && !HiddenPropertyMaskUtils.isMaskedPlaceholder(providers)) {
      return providers;
    }
    try {
      return catalogManager.doWithCatalog(
          identifier,
          loadedCatalog ->
              (String)
                  loadedCatalog
                      .propertiesWithCredentialProviders()
                      .get(CredentialConstants.CREDENTIAL_PROVIDERS));
    } catch (RuntimeException e) {
      LOG.warn(
          "Failed to resolve credential providers for catalog {}; "
              + "the credential type will be unavailable",
          identifier,
          e);
      return null;
    }
  }
}
