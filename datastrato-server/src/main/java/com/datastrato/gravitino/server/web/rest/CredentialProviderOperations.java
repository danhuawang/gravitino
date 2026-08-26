/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.dto.requests.CredentialProviderTestRequest;
import com.google.common.annotations.VisibleForTesting;
import java.util.Collections;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.credential.CredentialProvider;
import org.apache.gravitino.credential.CredentialProviderFactory;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.dto.responses.BaseResponse;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides enterprise REST APIs for credential providers. */
@Path("/web/credential-providers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces("application/vnd.gravitino.v1+json")
public class CredentialProviderOperations {

  private static final Logger LOG = LoggerFactory.getLogger(CredentialProviderOperations.class);

  @Context private HttpServletRequest httpRequest;

  /**
   * Tests whether a credential provider can be initialized and generate a credential.
   *
   * @param credentialType The credential provider type.
   * @param request The credential provider configuration.
   * @return A successful response if the provider generates a credential, or an error response.
   */
  @POST
  @Path("/{credentialType}/test")
  @AuthorizationExpression(expression = "")
  public Response testCredentialProvider(
      @PathParam("credentialType") String credentialType, CredentialProviderTestRequest request) {
    LOG.info("Received request to test credential provider: {}", credentialType);
    if (StringUtils.isBlank(credentialType)) {
      return Utils.illegalArguments(
          "Credential type cannot be empty", new IllegalArgumentException());
    }

    try {
      request.validate();
    } catch (Exception e) {
      return Utils.illegalArguments("Invalid credential provider test request", e);
    }

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            try (CredentialProvider provider =
                createCredentialProvider(credentialType, request.getProperties())) {
              if (!provider
                  .getCredentialOptional(
                      new PathBasedCredentialContext(
                          PrincipalUtils.getCurrentUserName(),
                          Collections.singleton(request.getPath()),
                          Collections.singleton(request.getPath())))
                  .isPresent()) {
                throw new IllegalStateException(
                    String.format(
                        "Credential provider %s did not generate a credential", credentialType));
              }
              return Utils.ok(new BaseResponse());
            }
          });
    } catch (Exception e) {
      return Utils.internalError(
          String.format("Failed to test credential provider %s", credentialType), e);
    }
  }

  @VisibleForTesting
  CredentialProvider createCredentialProvider(
      String credentialType, Map<String, String> properties) {
    return CredentialProviderFactory.create(credentialType, properties);
  }
}
