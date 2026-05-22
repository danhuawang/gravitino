/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.qa.common.oauth2;

import java.io.IOException;
import org.apache.gravitino.client.OAuth2TokenProvider;

/**
 * An {@link OAuth2TokenProvider} that wraps a pre-fetched access token obtained via the
 * resource-owner password grant from Keycloak.
 *
 * <p>This provider returns the same token on every {@link #getAccessToken()} call. For short-lived
 * E2E tests this is sufficient, since the token lifetime (typically 5 minutes) exceeds the test
 * duration. Use {@link KeycloakAdminHelper#fetchUserToken(String, String)} to obtain the token.
 */
public class PasswordGrantTokenProvider extends OAuth2TokenProvider {

  private final String accessToken;

  /**
   * Creates a provider with a pre-fetched token.
   *
   * @param accessToken The access token string obtained from Keycloak password grant
   */
  public PasswordGrantTokenProvider(String accessToken) {
    this.accessToken = accessToken;
  }

  @Override
  public void close() throws IOException {
    // no-op
  }

  @Override
  protected String getAccessToken() {
    return accessToken;
  }
}
