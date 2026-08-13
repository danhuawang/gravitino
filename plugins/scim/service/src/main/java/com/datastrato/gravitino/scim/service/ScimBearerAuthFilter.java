/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.service.web.ScimHttpResponses;
import com.datastrato.gravitino.scim.service.web.ScimRequestPaths;
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

/**
 * Authenticates opaque {@code gravitino_scim_*} bearer tokens and enforces metalake scope on port
 * 9201.
 */
public class ScimBearerAuthFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ScimBearerAuthFilter.class);

  private final ScimTokenManager tokenManager;

  /** Creates a filter using the shared SCIM token manager. */
  public ScimBearerAuthFilter() {
    this(ScimTokenManager.getInstance());
  }

  /**
   * Creates a filter for tests.
   *
   * @param tokenManager token manager
   */
  ScimBearerAuthFilter(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    String path = ScimRequestPaths.resolveRequestPath(httpRequest);

    if (!ScimRequestPaths.isMetalakeScopedPath(path)) {
      chain.doFilter(request, response);
      return;
    }

    String metalakeName =
        ScimRequestPaths.metalakeFromPath(path)
            .orElseThrow(
                () ->
                    new ServletException(
                        "Invalid SCIM metalake path (expected /scim/v2/metalakes/{metalake}/...)"));

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
      ScimToken token = tokenManager.authenticateBearerToken(bearerToken, metalakeName);
      UserPrincipal principal = new UserPrincipal(token.getTokenName());
      httpRequest.setAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME, principal);
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
