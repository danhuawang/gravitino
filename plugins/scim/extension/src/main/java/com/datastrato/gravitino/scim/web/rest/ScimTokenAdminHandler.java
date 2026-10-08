/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.dto.ScimTokenDTO;
import com.datastrato.gravitino.scim.dto.ScimTokenSummaryDTO;
import com.datastrato.gravitino.scim.dto.requests.CreateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.requests.RotateScimTokenRequest;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenDeleteResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenListResponse;
import com.datastrato.gravitino.scim.dto.responses.ScimTokenResponse;
import com.datastrato.gravitino.scim.model.CreatedScimToken;
import com.datastrato.gravitino.scim.web.ScimOperationType;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.apache.gravitino.exceptions.NotFoundException;

/**
 * Shared SCIM token admin handlers used by the public and internal REST resources.
 *
 * <p>This class is not a JAX-RS resource. Path binding, metrics, and internal-origin checks stay on
 * {@link PublicScimTokenOperations} and {@link ScimTokenOperations}.
 */
public class ScimTokenAdminHandler {

  private final ScimTokenManager tokenManager;

  /**
   * Creates a handler backed by the given token manager.
   *
   * @param tokenManager the SCIM token manager
   */
  @Inject
  public ScimTokenAdminHandler(ScimTokenManager tokenManager) {
    this.tokenManager = tokenManager;
  }

  /**
   * Lists active SCIM tokens. Plaintext token values are never returned.
   *
   * @param httpRequest the HTTP request used for principal/context propagation
   * @return the token list response
   */
  public Response listTokens(HttpServletRequest httpRequest) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          List<ScimTokenSummaryDTO> tokens =
              tokenManager.listScimTokens().stream()
                  .map(ScimTokenSummaryDTO::from)
                  .collect(Collectors.toList());
          return ScimRESTUtils.ok(new ScimTokenListResponse(tokens));
        },
        "",
        ScimOperationType.LIST);
  }

  /**
   * Creates a SCIM integration token. The plaintext {@code tokenValue} is returned only once.
   *
   * @param httpRequest the HTTP request used for principal/context propagation
   * @param request the create request
   * @return the created token response
   */
  public Response createToken(HttpServletRequest httpRequest, CreateScimTokenRequest request) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          request.validate();
          CreatedScimToken created =
              tokenManager.createScimToken(request.getTokenName(), request.getExpiresInDays());
          return ScimRESTUtils.ok(new ScimTokenResponse(toTokenDto(created)));
        },
        request.getTokenName(),
        ScimOperationType.CREATE);
  }

  /**
   * Rotates the bearer secret for an existing named token.
   *
   * @param httpRequest the HTTP request used for principal/context propagation
   * @param tokenName the token name
   * @param request optional rotate request; {@code null} keeps the current expiry
   * @return the rotated token response
   */
  public Response rotateToken(
      HttpServletRequest httpRequest, String tokenName, @Nullable RotateScimTokenRequest request) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          if (request != null) {
            request.validate();
          }
          Integer expiresInDays = request == null ? null : request.getExpiresInDays();
          CreatedScimToken rotated = tokenManager.rotateScimToken(tokenName, expiresInDays);
          return ScimRESTUtils.ok(new ScimTokenResponse(toTokenDto(rotated)));
        },
        tokenName,
        ScimOperationType.ROTATE);
  }

  /**
   * Soft-deletes the named SCIM token.
   *
   * @param httpRequest the HTTP request used for principal/context propagation
   * @param tokenName the token name
   * @return the delete response
   */
  public Response deleteToken(HttpServletRequest httpRequest, String tokenName) {
    return ScimRESTUtils.doAs(
        httpRequest,
        () -> {
          if (!tokenManager.deleteScimToken(tokenName)) {
            throw new NotFoundException("SCIM token not found: %s", tokenName);
          }
          return ScimRESTUtils.ok(new ScimTokenDeleteResponse(true));
        },
        tokenName,
        ScimOperationType.DELETE);
  }

  private static ScimTokenDTO toTokenDto(CreatedScimToken created) {
    return ScimTokenDTO.of(created.getTokenName(), created.getTokenValue(), created.getExpiresAt());
  }
}
