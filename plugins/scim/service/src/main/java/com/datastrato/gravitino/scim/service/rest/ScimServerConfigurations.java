/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.rest;

import org.apache.directory.scim.server.configuration.ServerConfiguration;
import org.apache.directory.scim.spec.schema.ServiceProviderConfiguration;

/** Factory for SCIMple {@link ServerConfiguration} advertised by Gravitino SCIM. */
final class ScimServerConfigurations {

  private ScimServerConfigurations() {}

  /**
   * Creates the Service Provider Configuration used by the SCIM Jersey application.
   *
   * <p>Bulk and ETag default to {@code true} in SCIMple {@code 1.0.0-M1} and have no public setters
   * for those flags. Gravitino does not implement Bulk or resource version / ETag, so advertise
   * unsupported to match runtime behavior.
   *
   * @return configured {@link ServerConfiguration}
   */
  static ServerConfiguration create() {
    ServerConfiguration configuration =
        new ServerConfiguration() {
          @Override
          public boolean isSupportsBulk() {
            return false;
          }

          @Override
          public boolean isSupportsETag() {
            return false;
          }
        };
    configuration
        .setId("gravitino-scim")
        .setDocumentationUri("https://github.com/datastrato/gravitino-enterprise")
        .addAuthenticationSchema(ServiceProviderConfiguration.AuthenticationSchema.oauthBearer())
        .setSupportsFilter(true)
        .setSupportsSort(false);
    configuration.setBulkMaxOperations(0);
    configuration.setBulkMaxPayloadSize(0);
    return configuration;
  }
}
