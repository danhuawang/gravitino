/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.integration.test;

import com.datastrato.gravitino.transit.openbao.OpenBaoTransitKmsClientFactory;
import org.apache.gravitino.encryption.kms.KmsClientFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;

@Tag("gravitino-docker-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenBaoTransitKmsClientIT extends AbstractTransitKmsClientIT {

  private static final String DEFAULT_IMAGE = "openbao/openbao:2.6.0";
  private static final String IMAGE_ENVIRONMENT_VARIABLE = "GRAVITINO_OPENBAO_DOCKER_IMAGE";

  @Override
  protected String image() {
    String configuredImage = System.getenv(IMAGE_ENVIRONMENT_VARIABLE);
    return configuredImage == null || configuredImage.trim().isEmpty()
        ? DEFAULT_IMAGE
        : configuredImage.trim();
  }

  @Override
  protected String executable() {
    return "bao";
  }

  @Override
  protected String addressEnvironmentVariable() {
    return "BAO_ADDR";
  }

  @Override
  protected String tokenEnvironmentVariable() {
    return "BAO_TOKEN";
  }

  @Override
  protected String api() {
    return OpenBaoTransitKmsClientFactory.API;
  }

  @Override
  protected KmsClientFactory factory() {
    return new OpenBaoTransitKmsClientFactory();
  }
}
