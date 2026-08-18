/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.openbao;

import com.datastrato.gravitino.transit.kms.TransitKmsClientFactorySupport;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientFactory;
import org.apache.gravitino.encryption.kms.KmsConfigurationException;

/** Creates clients for the OpenBao Transit secrets engine. */
public final class OpenBaoTransitKmsClientFactory implements KmsClientFactory {

  static final String PROVIDER_NAME = "OpenBao Transit";

  /** Canonical API identifier for OpenBao Transit. */
  public static final String API = "openbao-transit";

  /** Property containing the OpenBao HTTP(S) server base address. */
  public static final String SERVICE_ADDRESS = "endpoint.address";

  /** Property explicitly allowing plaintext HTTP for isolated development or tests. */
  public static final String ALLOW_INSECURE_HTTP = "endpoint.allowInsecureHttp";

  /** Property containing the OpenBao Transit mount path. */
  public static final String TRANSIT_MOUNT = "endpoint.transitMount";

  /** Property selecting the credential sourcing method. */
  public static final String CREDENTIAL_METHOD = "credential.method";

  /** Property containing the name of the environment variable holding the OpenBao bearer token. */
  public static final String CREDENTIAL_ENVIRONMENT_VARIABLE = "credential.environmentVariable";

  /** Default OpenBao Transit mount path. */
  public static final String DEFAULT_TRANSIT_MOUNT = "transit";

  private static final Set<String> SUPPORTED_PROPERTIES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  SERVICE_ADDRESS,
                  ALLOW_INSECURE_HTTP,
                  TRANSIT_MOUNT,
                  CREDENTIAL_METHOD,
                  CREDENTIAL_ENVIRONMENT_VARIABLE)));

  private final Function<String, String> environmentLookup;

  /** Creates an OpenBao Transit KMS client factory. */
  public OpenBaoTransitKmsClientFactory() {
    this(System::getenv);
  }

  OpenBaoTransitKmsClientFactory(Function<String, String> environmentLookup) {
    this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
  }

  /** {@inheritDoc} */
  @Override
  public String api() {
    return API;
  }

  /** {@inheritDoc} */
  @Override
  public KmsClient create(String source, Map<String, String> properties) {
    TransitKmsClientFactorySupport.validateSource(PROVIDER_NAME, source);
    TransitKmsClientFactorySupport.validateProperties(
        PROVIDER_NAME, properties, SUPPORTED_PROPERTIES);

    boolean allowInsecureHttp =
        TransitKmsClientFactorySupport.optionalBooleanProperty(
            PROVIDER_NAME, properties, ALLOW_INSECURE_HTTP, false);
    URI serviceAddress =
        TransitKmsClientFactorySupport.parseServiceAddress(
            PROVIDER_NAME,
            TransitKmsClientFactorySupport.requireProperty(
                PROVIDER_NAME, properties, SERVICE_ADDRESS),
            allowInsecureHttp);
    String credentialMethod =
        TransitKmsClientFactorySupport.requireProperty(
            PROVIDER_NAME, properties, CREDENTIAL_METHOD);
    if (!"environment_variable".equals(credentialMethod)) {
      throw new KmsConfigurationException(
          "Unsupported OpenBao Transit credential method: %s", credentialMethod);
    }

    String transitMount = properties.get(TRANSIT_MOUNT);
    if (transitMount == null) {
      transitMount = DEFAULT_TRANSIT_MOUNT;
    } else {
      transitMount = transitMount.trim();
    }
    TransitKmsClientFactorySupport.validateTransitMount(PROVIDER_NAME, transitMount);
    String environmentVariable =
        TransitKmsClientFactorySupport.requireProperty(
            PROVIDER_NAME, properties, CREDENTIAL_ENVIRONMENT_VARIABLE);
    String bearerToken =
        TransitKmsClientFactorySupport.resolveEnvironmentCredential(
            PROVIDER_NAME, environmentVariable, environmentLookup);

    return new OpenBaoTransitClient(
        source, serviceAddress, transitMount, bearerToken, allowInsecureHttp);
  }
}
