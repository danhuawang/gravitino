/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Shared, thread-safe connection to a Transit-compatible service.
 *
 * <p>The connection owns one pooled HTTP client, the service origin, and one bearer token. Typed
 * API clients compose over this class and own their domain-specific paths and response mapping.
 * Callers must close the connection exactly once through its owning provider client.
 */
public final class TransitConnection implements AutoCloseable {

  private static final String TOKEN_HEADER = "X-Vault-Token";
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int REQUEST_TIMEOUT_MILLIS = 10_000;
  private static final int MAX_TOTAL_CONNECTIONS = 25;
  private static final int MAX_CONNECTIONS_PER_ROUTE = 25;

  private final String providerName;
  private final URI serviceAddress;
  private final String bearerToken;
  private final CloseableHttpClient httpClient;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates a shared connection with a bearer token resolved by the provider factory.
   *
   * @param providerName provider name used in errors
   * @param serviceAddress validated HTTP(S) service origin
   * @param bearerToken validated bearer token
   */
  public TransitConnection(String providerName, URI serviceAddress, String bearerToken) {
    this(providerName, serviceAddress, bearerToken, false);
  }

  /**
   * Creates a shared connection with an explicit plaintext-HTTP policy.
   *
   * @param providerName provider name used in errors
   * @param serviceAddress validated HTTP(S) service origin
   * @param bearerToken validated bearer token
   * @param allowInsecureHttp whether plaintext HTTP is explicitly allowed
   */
  public TransitConnection(
      String providerName, URI serviceAddress, String bearerToken, boolean allowInsecureHttp) {
    this.providerName = providerName;
    this.serviceAddress = validateServiceAddress(providerName, serviceAddress, allowInsecureHttp);
    this.bearerToken = TransitClientFactorySupport.validateBearerToken(providerName, bearerToken);
    this.httpClient = createHttpClient();
  }

  TransitConnection(
      String providerName, URI serviceAddress, String bearerToken, CloseableHttpClient httpClient) {
    this(providerName, serviceAddress, bearerToken, false, httpClient);
  }

  TransitConnection(
      String providerName,
      URI serviceAddress,
      String bearerToken,
      boolean allowInsecureHttp,
      CloseableHttpClient httpClient) {
    this.providerName = providerName;
    this.serviceAddress = validateServiceAddress(providerName, serviceAddress, allowInsecureHttp);
    this.bearerToken = TransitClientFactorySupport.validateBearerToken(providerName, bearerToken);
    this.httpClient = httpClient;
  }

  TransitHttpResponse get(List<String> pathSegments) {
    checkOpen();
    URI requestUri = requestUri(pathSegments);
    return executeGet(requestUri);
  }

  /** Closes the owned HTTP connection pool. Repeated calls have no effect. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      httpClient.close();
    } catch (IOException e) {
      throw new ConnectionFailedException(e, "Failed to close the %s HTTP client", providerName);
    }
  }

  private TransitHttpResponse executeGet(URI requestUri) {
    checkOpen();
    HttpGet request = new HttpGet(requestUri);
    request.setHeader("Accept", "application/json");
    request.setHeader(TOKEN_HEADER, bearerToken);
    try {
      return httpClient.execute(
          request,
          response ->
              new TransitHttpResponse(response.getCode(), readResponse(response.getEntity())));
    } catch (ConnectionFailedException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new ConnectionFailedException(e, "%s is unavailable", providerName);
    }
  }

  private URI requestUri(List<String> pathSegments) {
    if (pathSegments == null || pathSegments.isEmpty()) {
      throw new IllegalArgumentException("Transit request path cannot be empty");
    }
    String encodedPath =
        pathSegments.stream()
            .map(TransitConnection::requirePathSegment)
            .map(TransitConnection::encodePathSegment)
            .collect(Collectors.joining("/"));
    return URI.create(String.format("%s/v1/%s", serviceAddress, encodedPath));
  }

  private void checkOpen() {
    if (closed.get()) {
      throw new ConnectionFailedException("%s connection is closed", providerName);
    }
  }

  private static byte[] readResponse(HttpEntity entity) throws IOException {
    if (entity == null) {
      return new byte[0];
    }
    if (entity.getContentLength() > MAX_RESPONSE_BYTES) {
      throw new ConnectionFailedException("Transit response exceeds the allowed size");
    }

    try (InputStream responseBody = entity.getContent();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int remaining = MAX_RESPONSE_BYTES + 1;
      while (remaining > 0) {
        int read = responseBody.read(buffer, 0, Math.min(buffer.length, remaining));
        if (read < 0) {
          break;
        }
        output.write(buffer, 0, read);
        remaining -= read;
      }
      if (output.size() > MAX_RESPONSE_BYTES) {
        throw new ConnectionFailedException("Transit response exceeds the allowed size");
      }
      return output.toByteArray();
    }
  }

  private static String requirePathSegment(String segment) {
    if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
      throw new IllegalArgumentException("Transit request path segment is invalid");
    }
    return segment;
  }

  private static URI validateServiceAddress(
      String providerName, URI serviceAddress, boolean allowInsecureHttp) {
    if (serviceAddress == null) {
      throw new TransitConfigurationException(
          String.format("Invalid %s endpoint address", providerName));
    }
    return TransitClientFactorySupport.parseServiceAddress(
        providerName, serviceAddress.toString(), allowInsecureHttp);
  }

  private static String encodePathSegment(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    } catch (IOException e) {
      throw new ConnectionFailedException(e, "Failed to encode a Transit request path");
    }
  }

  private static CloseableHttpClient createHttpClient() {
    Timeout connectTimeout = Timeout.ofMilliseconds(CONNECT_TIMEOUT_MILLIS);
    Timeout requestTimeout = Timeout.ofMilliseconds(REQUEST_TIMEOUT_MILLIS);
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(connectTimeout)
            .setSocketTimeout(requestTimeout)
            .build();
    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(requestTimeout)
            .setResponseTimeout(requestTimeout)
            .build();
    return HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(MAX_TOTAL_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .build())
        .setDefaultRequestConfig(requestConfig)
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .build();
  }
}
