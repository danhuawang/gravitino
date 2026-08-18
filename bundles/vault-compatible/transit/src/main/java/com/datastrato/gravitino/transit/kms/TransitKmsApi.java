/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.kms;

import com.datastrato.gravitino.transit.common.TransitAuthenticationException;
import com.datastrato.gravitino.transit.common.TransitConnection;
import com.datastrato.gravitino.transit.common.TransitHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Optional;
import org.apache.gravitino.encryption.kms.KmsAuthenticationException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.hc.core5.http.HttpStatus;

final class TransitKmsApi {

  private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

  private final String providerName;
  private final String transitMount;
  private final TransitConnection connection;

  TransitKmsApi(String providerName, String transitMount, TransitConnection connection) {
    this.providerName = providerName;
    this.transitMount = transitMount;
    this.connection = connection;
  }

  Optional<TransitReadKeyResponse> readKey(String keyId) {
    TransitHttpResponse result;
    try {
      result = connection.readKey(transitMount, keyId);
    } catch (TransitAuthenticationException e) {
      throw new KmsAuthenticationException(e, "%s", e.getMessage());
    }

    if (result.statusCode() == HttpStatus.SC_NOT_FOUND) {
      if (isMissingKeyResponse(result.body())) {
        return Optional.empty();
      }
      throw new ConnectionFailedException(
          "%s could not inspect key %s because the Transit route was not found",
          providerName, keyId);
    }
    if (isAuthenticationFailure(result.statusCode())) {
      throw new KmsAuthenticationException(
          "%s rejected its configured credentials (HTTP %s)", providerName, result.statusCode());
    }
    if (result.statusCode() < HttpStatus.SC_SUCCESS
        || result.statusCode() >= HttpStatus.SC_REDIRECTION) {
      throw new ConnectionFailedException(
          "%s could not inspect key %s (HTTP %s)", providerName, keyId, result.statusCode());
    }

    try {
      return Optional.of(OBJECT_MAPPER.readValue(result.body(), TransitReadKeyResponse.class));
    } catch (IOException | RuntimeException e) {
      throw new ConnectionFailedException(e, "%s returned a malformed response", providerName);
    }
  }

  private static boolean isAuthenticationFailure(int statusCode) {
    return statusCode == HttpStatus.SC_UNAUTHORIZED || statusCode == HttpStatus.SC_FORBIDDEN;
  }

  private static boolean isMissingKeyResponse(byte[] body) {
    try {
      JsonNode response = OBJECT_MAPPER.readTree(body);
      JsonNode errors = response == null ? null : response.get("errors");
      return errors != null && errors.isArray() && errors.size() == 0;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static ObjectMapper createObjectMapper() {
    return JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
  }
}
