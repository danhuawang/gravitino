/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web;

import java.security.PrivilegedExceptionAction;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** REST helpers for SCIM token admin resources. */
public final class ScimRESTUtils {

  private static final Logger LOG = LoggerFactory.getLogger(ScimRESTUtils.class);

  private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;

  private ScimRESTUtils() {}

  /**
   * Returns an OK JSON response for the given entity.
   *
   * @param entity response payload
   * @return HTTP 200 response
   */
  public static <T> Response ok(T entity) {
    return json(Response.Status.OK, entity);
  }

  /**
   * Executes the action under the authenticated request principal.
   *
   * @param httpRequest current HTTP request
   * @param action action to execute
   * @return action response
   * @throws Exception if the action fails
   */
  public static Response doAs(
      HttpServletRequest httpRequest, PrivilegedExceptionAction<Response> action) throws Exception {
    UserPrincipal principal =
        (UserPrincipal)
            httpRequest.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME);
    return PrincipalUtils.doAs(
        principal == null ? new UserPrincipal(AuthConstants.ANONYMOUS_USER) : principal, action);
  }

  /**
   * Executes the action and maps known exceptions to REST error responses.
   *
   * @param httpRequest current HTTP request
   * @param action action to execute
   * @param metalake target metalake name
   * @param tokenName token name for logging, may be blank
   * @param op operation type
   * @return HTTP response
   */
  public static Response doAs(
      HttpServletRequest httpRequest,
      PrivilegedExceptionAction<Response> action,
      String metalake,
      String tokenName,
      ScimOperationType op) {
    try {
      return doAs(httpRequest, action);
    } catch (Exception e) {
      String errorMsg =
          String.format(
              "Failed to operate SCIM token [%s] in metalake [%s] operation [%s], reason [%s]",
              StringUtils.defaultString(tokenName),
              StringUtils.defaultString(metalake),
              op.name(),
              e.getMessage());
      LOG.warn(errorMsg, e);
      return toErrorResponse(errorMsg, e);
    }
  }

  private static Response toErrorResponse(String errorMsg, Exception e) {
    if (e instanceof IllegalArgumentException) {
      return illegalArguments(errorMsg, e);
    }
    if (e instanceof NotFoundException) {
      return notFound(errorMsg, e);
    }
    if (e instanceof AlreadyExistsException) {
      return alreadyExists(errorMsg, e);
    }
    if (e instanceof IllegalStateException || e instanceof UnsupportedOperationException) {
      return unsupportedOperation(errorMsg, e);
    }
    return internalError(errorMsg, e);
  }

  private static Response illegalArguments(String message, Throwable throwable) {
    return json(
        Response.Status.BAD_REQUEST,
        ErrorResponse.illegalArguments(
            exceptionType(throwable, "IllegalArgumentException"), message, throwable));
  }

  private static Response notFound(String message, Throwable throwable) {
    return json(
        Response.Status.NOT_FOUND,
        ErrorResponse.notFound(exceptionType(throwable, "NotFoundException"), message, throwable));
  }

  private static Response alreadyExists(String message, Throwable throwable) {
    return json(
        Response.Status.CONFLICT,
        ErrorResponse.alreadyExists(
            exceptionType(throwable, "AlreadyExistsException"), message, throwable));
  }

  private static Response unsupportedOperation(String message, Throwable throwable) {
    return json(
        Response.Status.METHOD_NOT_ALLOWED, ErrorResponse.unsupportedOperation(message, throwable));
  }

  private static Response internalError(String message, Throwable throwable) {
    return json(
        Response.Status.INTERNAL_SERVER_ERROR, ErrorResponse.internalError(message, throwable));
  }

  private static Response json(Response.Status status, Object entity) {
    return Response.status(status).entity(entity).type(JSON).build();
  }

  private static String exceptionType(Throwable throwable, String defaultType) {
    return throwable == null ? defaultType : throwable.getClass().getSimpleName();
  }
}
