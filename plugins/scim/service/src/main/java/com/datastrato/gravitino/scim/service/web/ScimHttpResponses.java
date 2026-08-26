/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Writes SCIM protocol error bodies for the auxiliary HTTP listener on port 9201. */
public final class ScimHttpResponses {

  /** SCIM error schema URN. */
  public static final String SCIM_ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ScimHttpResponses() {}

  /**
   * Writes a SCIM {@code Error} response.
   *
   * @param response servlet response
   * @param status HTTP status code
   * @param detail human-readable detail message
   * @throws IOException if writing the response fails
   */
  public static void writeError(HttpServletResponse response, int status, String detail)
      throws IOException {
    response.resetBuffer();
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType("application/scim+json");
    if (response instanceof ScimCapturingResponse captured) {
      captured.noteDetail(detail);
    }
    MAPPER.writeValue(
        response.getWriter(),
        Map.of(
            "schemas", List.of(SCIM_ERROR_SCHEMA),
            "status", String.valueOf(status),
            "detail", detail));
  }
}
