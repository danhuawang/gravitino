/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.catalog.connection.ConnectionTestResult;
import com.datastrato.gravitino.catalog.connection.ConnectionTestStore;
import com.datastrato.gravitino.catalog.connection.ConnectionTestType;
import com.datastrato.gravitino.dto.requests.CredentialProviderTestRequest;
import com.google.common.annotations.VisibleForTesting;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.credential.CredentialProvider;
import org.apache.gravitino.credential.CredentialProviderFactory;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.credential.config.CredentialConfig;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides enterprise REST APIs for credential providers. */
@Path("/web/metalakes/{metalake}/connections/{connection}/credential-providers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces("application/vnd.gravitino.v1+json")
public class CredentialProviderOperations {

  private static final Logger LOG = LoggerFactory.getLogger(CredentialProviderOperations.class);
  private static final String SAFE_CREDENTIAL_FAILURE_MESSAGE =
      "Failed to test the credential provider";

  private final ConnectionTestStore connectionTestStore;
  private final CatalogDispatcher catalogDispatcher;
  private final Clock clock;

  @Context private HttpServletRequest httpRequest;

  /**
   * Creates credential provider REST operations using the system clock.
   *
   * @param catalogDispatcher The Catalog dispatcher.
   * @param connectionTestStore The persistent connection test store.
   */
  @Inject
  public CredentialProviderOperations(
      CatalogDispatcher catalogDispatcher, ConnectionTestStore connectionTestStore) {
    this(catalogDispatcher, connectionTestStore, Clock.systemUTC());
  }

  @VisibleForTesting
  CredentialProviderOperations(
      CatalogDispatcher catalogDispatcher, ConnectionTestStore connectionTestStore, Clock clock) {
    this.catalogDispatcher = catalogDispatcher;
    this.connectionTestStore = connectionTestStore;
    this.clock = clock;
  }

  /**
   * Tests a credential provider configured on an existing connection and persists the result.
   *
   * @param metalake The metalake name.
   * @param connection The Catalog name used as the connection name.
   * @param credentialType The canonical credential provider type.
   * @param request The storage path used for the credential probe.
   * @return A successful response when a credential is generated, or an error response.
   */
  @POST
  @Path("/{credentialType}/test")
  @AuthorizationExpression(
      expression = "ANY(OWNER, METALAKE, CATALOG)",
      accessMetadataType = MetadataObject.Type.CATALOG)
  public Response testExistingCredentialProvider(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @PathParam("connection") @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          String connection,
      @PathParam("credentialType") String credentialType,
      CredentialProviderTestRequest request) {
    LOG.info(
        "Received request to test credential provider {} for connection {}.{}",
        credentialType,
        metalake,
        connection);

    final String testType;
    try {
      request.validate();
      testType = ConnectionTestType.credential(credentialType);
    } catch (Exception e) {
      LOG.warn(
          "Invalid credential provider test request for {} on connection {}.{}",
          credentialType,
          metalake,
          connection,
          e);
      return Utils.illegalArguments("Invalid credential provider test request");
    }

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifierUtil.ofCatalog(metalake, connection);
            Catalog catalog = catalogDispatcher.loadCatalog(identifier);
            Map<String, String> properties = credentialProperties(catalog);
            Map<String, String> persistedProperties = persistedProperties(catalog);
            if (!isCredentialProviderConfigured(credentialType, properties)) {
              return Utils.illegalArguments(
                  String.format(
                      "Credential provider %s is not configured on Catalog %s",
                      credentialType, identifier));
            }

            try {
              testCredentialProvider(credentialType, request.getPath(), properties);
            } catch (Exception e) {
              connectionTestStore.recordTestResult(
                  identifier,
                  catalog.provider(),
                  persistedProperties,
                  testType,
                  ConnectionTestResult.Status.FAILED,
                  clock.millis(),
                  SAFE_CREDENTIAL_FAILURE_MESSAGE);
              throw e;
            }

            boolean recorded =
                connectionTestStore.recordTestResult(
                    identifier,
                    catalog.provider(),
                    persistedProperties,
                    testType,
                    ConnectionTestResult.Status.PASSED,
                    clock.millis(),
                    null);
            if (!recorded) {
              return Utils.internalError(
                  "Credential provider test result could not be recorded because the Catalog "
                      + "configuration changed or a newer result already exists");
            }
            return Utils.ok(new BaseResponse());
          });
    } catch (NoSuchCatalogException e) {
      return ExceptionHandlers.handleCatalogException(OperationType.LOAD, connection, metalake, e);
    } catch (Exception e) {
      LOG.error(
          "Failed to test credential provider {} for connection {}.{}",
          credentialType,
          metalake,
          connection,
          e);
      return Utils.internalError(
          String.format(
              "Failed to test credential provider %s for connection %s.%s",
              credentialType, metalake, connection));
    }
  }

  private void testCredentialProvider(
      String credentialType, String path, Map<String, String> properties) throws Exception {
    try (CredentialProvider provider = createCredentialProvider(credentialType, properties)) {
      if (!provider
          .getCredentialOptional(
              new PathBasedCredentialContext(
                  PrincipalUtils.getCurrentUserName(),
                  Collections.singleton(path),
                  Collections.singleton(path)))
          .isPresent()) {
        throw new IllegalStateException(
            String.format("Credential provider %s did not generate a credential", credentialType));
      }
    }
  }

  private Map<String, String> credentialProperties(Catalog catalog) {
    Map<String, String> properties =
        catalog instanceof BaseCatalog
            ? ((BaseCatalog<?>) catalog).propertiesWithCredentialProviders()
            : catalog.properties();
    return properties == null ? Collections.emptyMap() : properties;
  }

  private Map<String, String> persistedProperties(Catalog catalog) {
    Map<String, String> properties =
        catalog instanceof BaseCatalog
            ? ((BaseCatalog<?>) catalog).entity().getProperties()
            : catalog.properties();
    return properties == null ? Collections.emptyMap() : properties;
  }

  private boolean isCredentialProviderConfigured(
      String credentialType, Map<String, String> properties) {
    CredentialConfig credentialConfig = new CredentialConfig(properties);
    return credentialConfig.get(CredentialConfig.CREDENTIAL_PROVIDERS).contains(credentialType);
  }

  @VisibleForTesting
  CredentialProvider createCredentialProvider(
      String credentialType, Map<String, String> properties) {
    return CredentialProviderFactory.create(credentialType, properties);
  }
}
