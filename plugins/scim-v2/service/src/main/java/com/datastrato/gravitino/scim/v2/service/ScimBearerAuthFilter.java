/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service;

import com.datastrato.gravitino.scim.v2.ScimTokenManager;
import com.datastrato.gravitino.scim.v2.model.ScimToken;
import com.datastrato.gravitino.scim.v2.service.web.ScimHttpResponses;
import com.datastrato.gravitino.scim.v2.service.web.ScimRequestPaths;
import com.google.common.base.Strings;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.exceptions.TokenExpiredException;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authenticates opaque {@code gravitino_scim_v2_*} bearer tokens on port 9201. */
public class ScimBearerAuthFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ScimBearerAuthFilter.class);
  private final ScimTokenManager tokenManager;

  public ScimBearerAuthFilter() {
    this(ScimTokenManager.getInstance());
  }

  ScimBearerAuthFilter(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    String path = ScimRequestPaths.resolveRequestPath(httpRequest);

    if (!ScimRequestPaths.isScimV2ResourcePath(path)) {
      chain.doFilter(request, response);
      return;
    }

    String authorizationHeader = httpRequest.getHeader(AuthConstants.HTTP_HEADER_AUTHORIZATION);
    if (Strings.isNullOrEmpty(authorizationHeader)) {
      ScimHttpResponses.writeError(
          httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
      return;
    }

    String bearerToken = extractBearerToken(authorizationHeader);
    if (bearerToken == null) {
      ScimHttpResponses.writeError(
          httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
      return;
    }

    try {
      ScimToken token = tokenManager.authenticateBearerToken(bearerToken);
      UserPrincipal principal = new UserPrincipal(token.getTokenName());
      httpRequest.setAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME, principal);
      updateScimTokenLastUsedAt(token);
      PrincipalUtils.doAs(
          principal,
          () -> {
            chain.doFilter(request, response);
            return null;
          });
    } catch (NotFoundException e) {
      ScimHttpResponses.writeError(httpResponse, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
    } catch (TokenExpiredException e) {
      ScimHttpResponses.writeError(httpResponse, 419, e.getMessage());
    } catch (UnauthorizedException e) {
      ScimHttpResponses.writeError(
          httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    } catch (RuntimeException e) {
      LOG.warn("Unexpected SCIM bearer authentication failure", e);
      ScimHttpResponses.writeError(
          httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    } catch (Exception e) {
      throw new ServletException(e);
    }
  }

  private void updateScimTokenLastUsedAt(ScimToken token) {
    try {
      tokenManager.updateScimTokenLastUsedAt(token.getTokenId());
    } catch (RuntimeException e) {
      LOG.warn("Failed to update last_used_at for SCIM token {}", token.getTokenId(), e);
    }
  }

  private static String extractBearerToken(String authorizationHeader) {
    if (Strings.isNullOrEmpty(authorizationHeader)) {
      return null;
    }
    if (!authorizationHeader.regionMatches(
        true,
        0,
        AuthConstants.AUTHORIZATION_BEARER_HEADER,
        0,
        AuthConstants.AUTHORIZATION_BEARER_HEADER.length())) {
      return null;
    }
    String token =
        authorizationHeader.substring(AuthConstants.AUTHORIZATION_BEARER_HEADER.length());
    return Strings.isNullOrEmpty(token) ? null : token.trim();
  }
}
